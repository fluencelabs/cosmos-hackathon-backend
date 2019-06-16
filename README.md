![logo](img/salmon.png)

- Backend here
- Frontend [there](https://github.com/fluencelabs/cosmos-hackathon-frontend)
 
## If you want your Zone to be validated by Cosmic Salmon, reach out to @folexeyy on Telegram

# How to run
```bash
sbt assembly
java -jar ./target/scala-2.12/http-scala-api-assembly-0.1.jar
```

# API
### Retreieve running apps
```bash
curl http://salmon.fluence.one:8080/apps
```
```json
[
  {
    "name" : "nameservice",
    "network" : "namechain",
    "binaryHash" : "QmQ69JoPDaKFpPSbWvvUGRGG5E83u6nTP2hRegmCoa6aW5",
    "consensusHeight" : 69386,
    "validatorsCount" : 4
  },
  {
    "name" : "commercionetwork",
    "network" : "commercio-testnet1001",
    "binaryHash" : "QmepV645qHM9XR97KiK7Bvd2jjK3MiKTGbq8cYm7izRyQK",
    "consensusHeight" : 38783,
    "validatorsCount" : 4
  }
]
```
### Add your app on validation
- `nameservice` - any name for your app
- `207.154.210.117` - IP address of your validator node
- `26657` - RPC port
- `QmQ69JoPDaKFpPSbWvvUGRGG5E83u6nTP2hRegmCoa6aW5` - IPFS hash of the Zone app binary (built against glibc)

```bash
curl http://salmon.fluence.one:8080/create/nameservice/207.154.210.117/26657/QmQ69JoPDaKFpPSbWvvUGRGG5E83u6nTP2hRegmCoa6aW5"
```

### Stream validation results through websocket
JavaScript

```js
var ws = new WebSocket("ws://localhost:8080/websocket/commercionetwork")
ws.onmessage = console.log
```

For shell, use [websocat](https://github.com/vi/websocat).