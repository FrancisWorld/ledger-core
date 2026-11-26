# ledger-core

[English](README.md) | **Português (Brasil)**

[![CI](https://github.com/FrancisWorld/ledger-core/actions/workflows/ci.yml/badge.svg)](https://github.com/FrancisWorld/ledger-core/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

API de livro-razão contábil de partidas dobradas em Java 21 e Spring Boot. O dinheiro só se move por lançamentos balanceados, e as regras que mantêm a contabilidade honesta são aplicadas duas vezes: no modelo de domínio e, de novo, dentro do PostgreSQL.

## Por que existe e o que é difícil

Um ledger parece "atualizar dois saldos" até que concorrência, retentativas e falhas aparecem. Este projeto é uma resposta prática aos modos de falha mais comuns:

| Problema | Como é tratado |
| --- | --- |
| Duas requisições gastam o mesmo dinheiro | Linhas travadas com `SELECT ... FOR UPDATE`, sempre em ordem crescente de id da conta: transferências concorrentes se serializam por conta e não geram deadlock. Uma restrição `CHECK` reforça a regra de saldo no banco. |
| Cliente repete uma requisição que deu timeout | `Idempotency-Key` em todo `POST`. A linha da chave e as escritas de negócio confirmam na mesma transação, então a repetição devolve a resposta guardada e o dinheiro se move uma única vez. Mesma chave com corpo diferente é `409`. |
| Alguém altera o histórico | `entries` e `transactions` são append-only (triggers rejeitam `UPDATE`, `DELETE` e `TRUNCATE`) e um constraint trigger adiado impede o commit de uma transação cujos débitos e créditos difiram por moeda. Além disso o serviço conecta com uma role do banco que nem tem esses privilégios e não consegue remover nem desligar os triggers, então um serviço comprometido não reescreve o diário. |
| Quem pode chamar o quê | Chaves de API Bearer guardadas como digest SHA-256, cada endpoint exigindo um escopo (fechado por padrão; o ArchUnit quebra o build se um endpoint esquecer de declarar). As chaves de idempotência são separadas por chave de API. |
| Publicar um evento e gravar o dado podem divergir | Outbox transacional: eventos são gravados na mesma transação da mudança e retransmitidos com `FOR UPDATE SKIP LOCKED`, com entrega ao-menos-uma-vez. |
| Autorizações estilo cartão | Holds reservam fundos (`disponível = saldo - retido`), podem ser capturados (total ou parcialmente) ou liberados, e expiram sozinhos. |
| Erros operacionais | Estornos lançam uma nova transação espelhada; uma transação só pode ser estornada uma vez, garantido por índice único no banco. |

Valores são inteiros em unidades menores da moeda mais um código ISO 4217. Não há float nem decimal em lugar nenhum.

## Arquitetura

```mermaid
flowchart LR
  client([Cliente HTTP]) --> web
  subgraph app[ledger-core]
    web[adapter.in.web<br/>controllers, problem+json,<br/>idempotência] --> usecases
    subgraph core[hexágono]
      usecases[application<br/>casos de uso + portas] --> domain[domain<br/>Account, Posting, Hold]
    end
    usecases --> persistence[adapter.out.persistence<br/>repositórios JDBC]
    usecases --> messaging[adapter.out.messaging<br/>sink de log / SQS]
    jobs[bootstrap<br/>relay do outbox, varredura de holds] --> usecases
  end
  persistence --> pg[(PostgreSQL<br/>constraints + triggers)]
  messaging --> sqs[[fila SQS]]
  pg -. linhas do outbox .-> jobs
```

`domain` e `application` são Java puro, sem imports de framework. As regras do ArchUnit em `ArchitectureTest` quebram o build se uma dependência apontar para o lado errado (por exemplo a aplicação importar Spring ou JDBC, ou a camada web tocar em SQL).

## Início rápido

Requisitos: JDK 21 e Docker.

```bash
cp .env.example .env                  # senhas de exemplo, troque as duas
docker compose up --build -d          # postgres, um job de migração e depois o serviço
```

O compose sobe três coisas: PostgreSQL, um job `migrate` que aplica as migrações Flyway como dono do schema e termina, e o serviço, que conecta com a role sem privilégios `ledger_app` (sem DDL, sem UPDATE/DELETE no diário, sem mexer nos triggers). O serviço responde em `http://localhost:8080`; saúde e métricas ficam na porta interna de gerenciamento 8081, que o compose não publica.

Todo endpoint `/v1` exige uma chave de API (`Authorization: Bearer ...`) cujos escopos cubram a operação. As chaves são emitidas pelo job `keygen` (com credenciais do dono), exibidas uma única vez e guardadas só como digest SHA-256. O fluxo abaixo (contas, depósito, transferência, repetição idempotente, hold e captura, extrato, verificação de integridade) foi executado contra esta stack do compose; a saída vem a seguir. Rodei com `LEDGER_HTTP_PORT=18080` para evitar conflito de porta na minha máquina; os comandos usam o 8080 padrão.

```bash
export API=http://localhost:8080
export KEY=$(docker compose run --rm keygen 2>/dev/null | sed -n 's/^API_KEY=//p')
post() { curl -s -X POST "$API$1" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -H "Idempotency-Key: $2" -d "$3"; }
get() { curl -s "$API$1" -H "Authorization: Bearer $KEY"; }

CASH=$(post /v1/accounts quick-cash '{"code":"cash","name":"Cash","type":"ASSET","currency":"USD","allow_negative":true}' | jq -r .id)
ALICE=$(post /v1/accounts quick-alice '{"code":"alice","name":"Alice wallet","type":"LIABILITY","currency":"USD"}' | jq -r .id)
BOB=$(post /v1/accounts quick-bob '{"code":"bob","name":"Bob wallet","type":"LIABILITY","currency":"USD"}' | jq -r .id)
echo "cash=$CASH alice=$ALICE bob=$BOB"

# deposita 100,00 USD para a Alice: débito no caixa, crédito na carteira dela
post /v1/transactions quick-fund "{\"reference\":\"top-up\",\"lines\":[{\"account_id\":\"$CASH\",\"direction\":\"DEBIT\",\"amount\":10000},{\"account_id\":\"$ALICE\",\"direction\":\"CREDIT\",\"amount\":10000}]}" | jq -c '{kind, entries: [.entries[] | {direction, amount, balance_after}]}'

# transfere 25,00 para o Bob; repetir a requisição devolve a resposta guardada
post /v1/transfers quick-t1 "{\"source_account_id\":\"$ALICE\",\"destination_account_id\":\"$BOB\",\"amount\":2500,\"currency\":\"USD\"}" | jq -c '{kind, entries: [.entries[] | {direction, amount, balance_after}]}'
curl -si -X POST "$API/v1/transfers" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -H "Idempotency-Key: quick-t1" -d "{\"source_account_id\":\"$ALICE\",\"destination_account_id\":\"$BOB\",\"amount\":2500,\"currency\":\"USD\"}" | grep -iE '^(HTTP|idempotent-replayed)'

# reserva 30,00 na Alice e captura 20,00 dela para o Bob
HOLD=$(post /v1/holds quick-hold "{\"account_id\":\"$ALICE\",\"amount\":3000,\"currency\":\"USD\",\"reference\":\"order-1\"}" | jq -r .id)
get /v1/accounts/$ALICE | jq -c '{balance, held, available}'
post /v1/holds/$HOLD/capture quick-capture "{\"destination_account_id\":\"$BOB\",\"amount\":2000}" | jq -c '{hold: .hold.status, captured: .hold.captured_amount}'
get /v1/accounts/$ALICE | jq -c '{balance, held, available}'

# extrato: cada lançamento com o saldo corrente
get "/v1/accounts/$ALICE/statement?limit=10" | jq -c '.lines[] | {seq, kind, direction, amount, balance_after}'

# verificação de integridade
get /v1/trial-balance | jq -c .

# sem chave
curl -s -o /dev/null -w "no key: %{http_code}\n" "$API/v1/trial-balance"
```

Saída dessa execução (ids são UUIDv7; valores em unidades menores, então 10000 é 100,00 USD):

```text
cash=01a2154c-1a6f-7287-bc11-49732cec0997 alice=01a2154c-1d0e-7301-9226-3f8178e2dfc4 bob=01a2154c-1e08-7505-86d0-1a68c0b44739
{"kind":"JOURNAL","entries":[{"direction":"DEBIT","amount":10000,"balance_after":10000},{"direction":"CREDIT","amount":10000,"balance_after":10000}]}
{"kind":"TRANSFER","entries":[{"direction":"DEBIT","amount":2500,"balance_after":7500},{"direction":"CREDIT","amount":2500,"balance_after":2500}]}
HTTP/1.1 201 
Idempotent-Replayed: true
{"balance":7500,"held":3000,"available":4500}
{"hold":"CAPTURED","captured":2000}
{"balance":5500,"held":0,"available":5500}
{"seq":2,"kind":"JOURNAL","direction":"CREDIT","amount":10000,"balance_after":10000}
{"seq":3,"kind":"TRANSFER","direction":"DEBIT","amount":2500,"balance_after":7500}
{"seq":5,"kind":"CAPTURE","direction":"DEBIT","amount":2000,"balance_after":5500}
{"balanced":true,"currencies":[{"currency":"USD","debit_normal_total":10000,"credit_normal_total":10000,"drifted_accounts":0,"balanced":true}]}
no key: 401
```

O Swagger UI fica em `/swagger-ui` e o documento OpenAPI em `/v3/api-docs` (ambos públicos; desligue com `springdoc.api-docs.enabled=false` se não quiser expô-los). A saúde fica em `:8081/actuator/health` dentro da rede dos containers, e as métricas Prometheus em `:8081/actuator/prometheus`.

Escopos: `accounts:read`, `accounts:write`, `transactions:read`, `transactions:write`, `holds:read`, `holds:write`, `reports:read`. Emita uma chave mais restrita com `LEDGER_KEY_NAME=reporting LEDGER_KEY_SCOPES=reports:read docker compose run --rm keygen`.

## Decisões de projeto e trade-offs

- **Lock pessimista em vez de retentativas otimistas.** A linha da conta é o ponto de contenção, e um ledger nunca deve rejeitar uma transferência válida só porque outra pessoa tocou na conta. Os locks seguem uma ordem total (id crescente), o que torna o desenho livre de deadlock, e uma transação toca no máximo 100 contas. O custo é que uma conta muito quente serializa seus escritores; veja Limitações.
- **O saldo é avaliado no estado final do lançamento**, não linha a linha, então a ordem das linhas dentro da transação é irrelevante. Contas podem optar por `allow_negative` (caixa, tesouraria, contas de compensação).
- **O tipo da conta define o lado normal.** Ativo e despesa crescem com débito; passivo, patrimônio e receita crescem com crédito. Assim a carteira de um cliente é um passivo de natureza credora e `balance` é sempre o número natural, positivo.
- **Cada lançamento guarda `balance_after`.** Extratos não precisam de função de janela, e o endpoint de balancete refaz todos os lançamentos para provar que cada saldo guardado bate com seu histórico.
- **A idempotência vive na mesma transação do trabalho.** Não existe estado "em andamento" que possa ficar preso depois de uma falha. O custo: uma requisição que falha (por exemplo saldo insuficiente) não deixa chave e é simplesmente reavaliada na repetição. A impressão digital é um hash da operação mais a re-serialização canônica do corpo já interpretado, então espaços e ordem dos campos não importam.
- **A entrega do outbox é ao-menos-uma-vez.** Uma falha depois que o broker aceitou a mensagem e antes do commit do lote reentrega, e os consumidores deduplicam pelo atributo `event_id`. Mensagens que falham repetidamente recuam exponencialmente e por fim são estacionadas como `DEAD`, em vez de travar a fila.
- **Holds** são restritos a contas de natureza credora (carteiras) e sempre exigem fundos reais: `allow_negative` deixa débitos estourarem a conta, mas nunca deixa um hold reservar dinheiro que ela não tem. Uma captura dissolve a reserva inteira: a parte capturada vira débito e o resto fica livre de novo. A expiração de holds trava as contas na mesma ordem crescente de id dos demais escritores, o que impede deadlock com transferências.
- **Aritmética verificada em todo lugar.** Saldos usam `Math.addExact`; `available = balance - held` satura em `Long.MIN_VALUE` em vez de dar a volta, com testes de propriedade nos limites do `long`.
- **Duas roles no banco.** O Flyway roda como dono do schema; o serviço roda como `ledger_app` (DML nas tabelas operacionais, só `SELECT`/`INSERT` no diário, somente leitura em `api_keys`). Como a role de runtime não tem `UPDATE` em `transactions`, os estornos se serializam com um advisory lock de transação em vez de `SELECT ... FOR UPDATE`. Na AWS, uma tarefa de migração avulsa guarda o segredo master e o serviço só enxerga o segredo da `ledger_app`.
- **JDBC puro (`JdbcClient`) em vez de JPA.** O comportamento interessante é o SQL, então ele é escrito e testado como SQL.
- **Identificadores UUIDv7** mantêm as inserções de chave primária aproximadamente ordenadas no tempo.

## Estratégia de testes

`./mvnw verify` roda tudo. Os testes de integração sobem o PostgreSQL 16 com Testcontainers.

| Camada | O que prova |
| --- | --- |
| Testes unitários do domínio | Lados normais, aritmética de saldo, overflow, máquina de estados do hold, validação do lançamento. |
| Propriedades (jqwik) | Em mundos e lançamentos aleatórios: a equação contábil sempre vale, lançamentos aceitos nunca deixam uma carteira negativa, a ordem das linhas não muda o resultado, um estorno restaura todos os saldos, os saldos correntes batem com o estado final. |
| `RuntimeRoleIT` | Conexão crua como a role de runtime: não faz UPDATE, DELETE nem TRUNCATE no diário, não desliga nem remove os triggers, não faz `SET session_replication_role`, DDL nem emite chaves de API. |
| `AuthenticationIT` | 401 sem chave ou com chave desconhecida, malformada ou revogada, 403 sem escopo (leitura não dá escrita), chaves de idempotência isoladas por cliente, chaves guardadas com hash, saúde e métricas só na porta de gerenciamento. |
| `DatabaseInvariantsIT` | SQL cru como dono do schema que ignora a aplicação: lançamentos desbalanceados falham no commit, o histórico não pode ser alterado, apagado ou truncado, contas não ficam negativas, lançamentos devem usar a moeda da conta. |
| `LedgerApiIT` | Comportamento HTTP de ponta a ponta, formatos `application/problem+json`, extratos e paginação, holds, estornos, OpenAPI e métricas. |
| `IdempotencyIT` | Repetições, conflito por reuso de chave, impressão digital canônica, nova tentativa após falha e 24 duplicatas concorrentes executando exatamente uma vez. |
| `ConcurrencyIT` | Expiração de holds disputando com transferências (sem deadlock) e carga mista de transferência / hold / captura / liberação / estorno vinda de várias threads, seguida de auditoria: o dinheiro se conserva, nada fica negativo, o retido é igual aos holds ativos, a cadeia de saldos correntes de cada conta está íntegra e o balancete fecha. Também transferências opostas (sem deadlock), gasto e holds disputados (nunca negativo) e estornos concorrentes (exatamente um vence). |
| `OutboxIT` | Eventos confirmam atomicamente com sua mudança, quatro relays concorrentes entregam cada mensagem uma vez, uma falha simulada após a entrega reentrega em vez de perder, mensagens venenosas recuam e são estacionadas. |
| `ArchitectureTest` | As regras de dependência hexagonais. |

Uma carga de correção, não um benchmark: o `ConcurrencyIT` roda 24 threads x 120 operações mistas (transferências, holds, capturas, liberações, estornos) sobre 12 carteiras; na minha última execução 2452 operações tiveram sucesso e 428 foram rejeitadas legitimamente (saldo insuficiente, hold já liquidado, já estornado), em cerca de 14 s, e depois todas as auditorias de invariantes passaram. Medido em um laptop Apple Silicon (M1, 8 GB) com PostgreSQL em uma VM Colima com `fsync=off`; o número não diz nada sobre capacidade de produção.

Para rodar os testes de integração sem Testcontainers, aponte-os para qualquer PostgreSQL 16 (por exemplo o do compose) com `LEDGER_TEST_DB_URL`, `LEDGER_TEST_DB_USER` e `LEDGER_TEST_DB_PASSWORD`. No Colima, o Testcontainers precisa de `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` e `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

O estilo de código é verificado pelo Spotless (google-java-format) e pelo Checkstyle, ambos ligados à fase `validate`; rode `./mvnw spotless:apply` para formatar. O compilador roda com `-Xlint:all -Werror`.

## Infraestrutura

`infra/` contém uma configuração OpenTofu para a AWS: VPC com sub-redes públicas e privadas, Application Load Balancer que só serve HTTPS (a porta 80 redireciona; `certificate_arn` é obrigatório e `ingress_cidrs` começa vazio, então nada é alcançável até você listar quem pode conectar), serviço ECS Fargate com autoscaling, RDS PostgreSQL, fila SQS com dead-letter queue para o relay do outbox, ECR e IAM de menor privilégio. As credenciais são separadas: o segredo master do RDS (gerado e guardado pelo RDS) só é legível por uma tarefa avulsa `migrate` que aplica migrações e emite chaves de API, enquanto o serviço lê um segredo `ledger_app` próprio e roda com o Flyway desligado. A senha da `ledger_app` é gerada pelo OpenTofu, portanto aparece no state; mantenha o state em backend criptografado. A role de execução do serviço puxa esta imagem, escreve neste log group e lê apenas o segredo de runtime; a role da tarefa só pode fazer `sqs:SendMessage` na fila de eventos. O health check do balanceador usa a porta de gerenciamento, para a qual nenhum listener encaminha.

Ela é apenas validada, nunca aplicada:

```bash
cd infra && tofu fmt -check && tofu init -backend=false && tofu validate
```

## Limitações

- As chaves de API têm escopos, mas não têm expiração, limite de taxa nem rotação self-service; são emitidas e revogadas pelo dono do banco (job `keygen` ou `update api_keys set revoked_at = now()`). Não há autorização por conta: uma chave com `transactions:write` move dinheiro entre quaisquer contas.
- O TLS termina no balanceador na AWS; o compose local usa HTTP puro no localhost.
- Trocar a senha da `ledger_app` exige alterar o segredo e rodar `ALTER ROLE` à mão; a migração V6 só define a senha ao criar a role.
- Uma única conta quente serializa seus escritores (lock de linha por conta). Não há sharding nem agrupamento de lançamentos.
- As chaves de idempotência são guardadas para sempre; não há rotina de expurgo por TTL.
- Só a resposta final de uma requisição bem-sucedida é guardada; requisições que falham são reexecutadas.
- A ordem do outbox é a de inserção, não garantida entre relays concorrentes, e a entrega é ao-menos-uma-vez.
- Banco único, sem particionamento de `entries`; o diário append-only cresce sem limite.
- Transferências entre moedas só são possíveis como lançamentos explícitos de várias linhas por contas de compensação; não há motor de câmbio.
- Apenas o Dockerfile e a configuração OpenTofu foram validados (build e `tofu validate`); a stack AWS nunca foi aplicada.

## Licença

[MIT](LICENSE)

---

Built by [Chico Oliveira](https://github.com/FrancisWorld)
