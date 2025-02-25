# Reserved route names

The Mercury foundation functions are also written using the same event-driven API in platform-core. 
The following route names are reserved for the internal system functions.

Please DO NOT use them in your application functions as it would disrupt the normal operation of the
event system and your application may not work as expected.

| Route name                    | Purpose                                | Modules                      |
| :-----------------------------|:---------------------------------------|:-----------------------------|
| actuator.services             | Reserved for actuator admin endpoint   | platform-core                |
| elastic.queue.cleanup         | Elastic queue clean up task            | platform-core                |
| distributed.tracing           | Distributed trace logger               | platform-core                |
| system.ws.server.cleanup      | Websocket server cleanup service       | platform-core                |
| rest.automation.auth.handler  | REST automation authenticator          | platform-core                |
| distributed.trace.processor   | Distributed trace aggregator           | User defined trace handler   |
| system.service.registry       | Distributed routing registry           | cloud connectors             |
| system.service.query          | Distributed routing query              | cloud connectors             |
| cloud.connector.health        | Cloud connector health service         | cloud connectors             |
| additional.info               | Additional info service                | cloud connectors             |
| cloud.manager                 | Cloud manager service                  | Cloud connectors             |
| presence.service              | Presence reporter service              | cloud connectors             |
| presence.housekeeper          | Presence housekeeper service           | cloud connectors             |
| cloud.connector               | Cloud event emitter                    | Cloud connectors             |
| async.http.request            | HTTP request event handler             | REST automation system       |
| async.http.response           | HTTP response event handler            | REST automation system       |
| language.pack.inbox           | RPC inbox handler                      | Language Connector           |
| language.pack.registry        | Routing registry                       | Language Connector           |
| pub.sub.controller            | Pub/sub handler                        | Language Connector           |
| object.streams.io             | Object stream manager                  | Language Connector           |
| cron.scheduler                | Cron job scheduler                     | Scheduler helper application |
| init.service.monitor.*        | reserved for event stream startup      | service monitor core         |
| completion.service.monitor.*  | reserved for event stream clean up     | service monitor core         |
| init.multiplex.*              | reserved for event stream startup      | cloud connector core         |
| completion.multiplex.*        | reserved for event stream clean up     | cloud connector core         |

# Distributed trace processor

The route name "distributed.trace.processor" is reserved for user defined trace handler. 
If you implement a function with this route name, it will receive trace metrics in a real-time basis. 
You may then decide how to persist the metrics. e.g. Elastic Search or a database.

---

| Appendix-III                              | Home                                     |
| :----------------------------------------:|:----------------------------------------:|
| [Additional features](APPENDIX-III.md)    | [Table of Contents](TABLE-OF-CONTENTS.md)|
