# Mercury

Reference implementation for event driven microservices using three levels of digital decoupling

## Welcome to the Mercury project

The Mercury project is created with one primary objective - 
`to make software easy to write, read, test, deploy, scale and manage`.

Event-driven and reactive programming is the industry trend. However, writing pub/sub and reactive code
can be challenging. While sharing the same theory, there are many different implementation of reactive
libraries such as Akka, Vertx, Spring Reactor, etc.

Mercury is a platform abstraction layer for in-memory and network event stream systems. It takes
event-driven programming to the next level of simplicity. You don't need to explicitly write listeners
and pub/sub code.

In addition to traditional pub/sub for digital decoupling among domains, your applications can handle
real-time events for request-response (RPC), drop-n-forget, callback, pipeline, workflow and streaming.

Under the hood, Eclipse Vertx is used as the in-memory event system. For network event streams, Kafka,
ActiveMQ, Hazelcast and TIBCO are encapsulated as 'cloud-connectors'.

Mercury uses Java 1.8 Lambda construct so that you can write a 'service' to encapsulate business logic
in an anonymous function. You don't need any special 'wiring' to make your service responds to requests.

One service can call another services using the 'Post Office' which is the event emitter that sends and
receives requests and responses as events.

The system has a built-in REST automation engine in the platform core library. It allows you to declare any
REST endpoints without code. REST automation turns HTTP requests and responses into asynchronous events for
high performance non-blocking processing. You can tell REST automation to route a URI path to a 'service'
that you declare as anonymous function. For backward compatibility, it can also proxy a HTTP call to an 
external REST endpoint.

It supports Java, Python and Node.js. Network event stream encapsulation is implemented in Java. It includes
a language connector subproject to serve Python and Node.js.

Mercury powered applications can be deployed in bare metal, VM, kubernetes and 'Function as a service' in
on-premises infrastructure, data centers or any cloud environments.

Mercury has been deployed in a few mission critical production systems for the last 5 years. Based on inputs and
observation from the fields, we have continuously improved the codebase.

We are now preparing for version 3.0. We will provide additional information soon.

Best regards, Cloud Native Microservices team, Accenture

October 2022

## Rationale

The microservices movement is gaining a lot of momentum in recent years. Very much inspired with the need to
modernize service-oriented architecture and to transform monolithic applications as manageable and reusable 
pieces, it was first mentioned in 2011 for an architectural style that defines an application as a set of 
loosely coupled single purpose services.

Classical model of microservices architecture often focuses in the use of REST as interface and the 
self-containment of data and process. Oftentimes, there is a need for inter-service communication because 
one service may consume another service. Usually this is done with a service broker. This is an elegant 
architectural concept. However, many production systems face operational challenges. In reality, it is 
quite difficult to decompose a solution down to functional level. This applies to both green field 
development or application modernization. As a result, many microservices modules are indeed smaller 
subsystems. Within a traditional microservice, business logic is tightly coupled with 3rd party 
and open sources libraries including cloud platform client components and drivers. 

Mercury addresses this limitation with 'platform abstaction' (aka 'hexagonal' pattern) such that
you can encapsulate some platform functionality with a functional wrapper, thus avoiding hard wiring
your application code with specific dependencies.

## User friendly reactive programming

`Summary`
- Completely and natively event driven
- Fully open source - Apache-2.0 license
- Simple configuration and simple API hides all the scaffolding code
- Code naturally, without worrying about asynchrony
- Naturally code in functional style, which makes code very portable
- Mix and match languages - currently Java and Python are supported, Node.js and dotnet are WIP
- Built-in distributed tracing
- Avoid going to the network when running in the same process, a huge performance boost for latency 
  critical applications
- Doesn't require all services to adopt, will provide the same benefits to those that do adopt it
- Learn and start coding in less than one hour

`Benefits`
- Developer productivity - both authoring and maintenance
- Improves latency of calls within the same process
- Enables fully reactive implementations
- Scalability
- Portability of code between containers and clouds
- Observability - know who is calling who and when, know where you are in workflows
- Improves future-proofing - as better eventing libraries/frameworks/platforms become available, they 
  can be brought in without changing a line of code

`Features`
- Multiple languages supported - currently Java and Python are supported, Node.js and dotnet are WIP
- Plug in your favorite event stream. Popular ones are already supported.
- Multiple eventing patterns supported - pub/sub, broadcast, command, etc.
- REST automation for event-based services
- Built in distributed tracing

`Gnarly Use Cases solved with Mercury`
- Workflows with event-based architecture, utilizing Saga pattern
- Support of "closed user groups" for added security

## Architecture principles

For simplicity, we advocate 3 architecture principles to write microservices

- minimalist
- event driven
- context bound

Minimalist means we want user software to be as small as possible. The Mercury framework allows you to 
write business logic down to functional level using simple input-process-output pattern.

Event driven promotes loose coupling. All functions should run concurrently and independently of each other.

Lastly, context bound means high level of encapsulation so that a function only expose API contract and 
nothing else.

### Platform abstraction

Mercury offers the highest level of decoupling where each piece of business logic can be expressed as 
an anonymous function. A microservices module is a collection of one or more functions. These functions 
connect to each other using events.

The framework hides the complexity of event-driven programming and cloud platform integration. 
For the latter, the service mesh interface is fully encapsulated so that user functions do not need 
to be aware of network connectivity and details of the cloud platform.

### Simple Input-Process-Output function

This is a best practice in software development. Input is fed into an anonymous function. 
It will process the input according to some API contract and produce some output.

This simplifies software development and unit tests.

### Terminology

1. `Microservices` - Generally, we use this term to refer to an architectural pattern where business 
   logic is encapsulated in a bounded context, the unit of service is minimalist and services are 
   consumed in an event driven manner.
2. `Microservices function` or simply `function` - This refers to the smallest unit that encapsulates 
   business logic or 3rd party library. As a best practice, we advocate clear separation of business 
   logic from proprietary libraries. By wrapping 3rd party libaries as functions, we can keep the event 
   API intact when switching to an alternative library.
3. `Microservices module` or `microservice` - This refers to a single unit of deployment that contains 
   at least one public function and optionally some private functions. All functions communicate with 
   each others through a memory based event stream system within the module. Each microservices module 
   can be independently deployed and scaled.
4. `Microservices application` - It is a collection of microservices modules running together as a single 
   application.
5. `User facing endpoints` - This refers to public API for REST, websocket or other communication protocols.
6. `Event API` - Microservices functions expose event APIs internally for inter-service communications. 
   For example, a user facing endpoint may use REST protocol. The HTTP request is then converted to an event 
   to a microservice.
7. `Real-time events` - Messages that are time sensitive. e.g. RPC requests and responses.
8. `Store-n-forward events` - Events that are not time sensitive. First, a calling function can send an event without 
   waiting for a response. Second, the called function may not even available when the event is delivered. e.g. data 
   pipeline applications.
9. `Streaming events` - This refers to continuous flow of events from one function to another. Note that streaming 
   can be either real-time or store-n-forward.
10. `public function` - This means the function is visible to all application containers and instances in the system.
11. `private function` - This means the function is visible only to functions in the same memory space inside 
    an application instance.
12. `route name` - Each service can be uniquely identified with a name.
13. `closed user groups` - Conceptually, we treat the underlying event stream system as a conduit where one group of 
    application instances can be logically separated from another group. 

### Post Office

The Mercury framework is a SDK with some cloud connectors and language packs, mainly the Platform and the Post Office 
APIs. The Platform API allows you to programmatically register functions with "route names". The Post Office API is 
used to send events from one service to another.

For example, the following codelet sends the text "hello world" from the caller to the function registered as 
"v1.some.service".

```java
//-------------------------------------------------

// an example service in container-1
Platform platform = Platform.getInstance();

LambdaFunction f = (headers, body, instance) -> {
	// do some business logic
	System.out.println("I got your request..."+body);
	return something
};
platform.register("v1.some.service", f, 1);

//-------------------------------------------------

// sending events from container-2
PostOffice po = PostOffice.getInstance();

// making a RPC call
EventEnvelope response = po.request("v1.some.service", 1000, "hello world");
System.out.println("I got response here..."+response.getBody());

// making a drop-and-forget async call
po.send("v1.some.service", "hello world");

//-------------------------------------------------

```

### Inter-service communication patterns

- RPC `“Request-response”, best for interactivity`
- Asynchronous `e.g. Drop-n-forget and long queries`
- Call back `e.g. Progressive rendering`
- Pipeline `e.g. Work-flow application`
- Streaming `e.g. Continuous data feed`
- Broadcast `e.g. Concurrent processing of the same dataset with different outcomes`


### How this repository is organized

The Mercury framework includes the following:
1. system `platform-core and rest-spring`
2. connectors `kafka, hazelcast, ...`
3. language packs `python, node.js...`
4. extensions `rest-automation, api-playground, ...`
5. documentation `architecture and user guides`
6. examples `code samples / templates for creating microservices with or without REST endpoints`


## Libraries and components

`platform core`

With Mercury, any software module can be expressed as an anonymous functions or a Java class that implements 
the LambdaFunction interface.

The platform core library enables the following:
1. High level of decoupling - You may write business logic as anonymous functions that run concurrently and 
   independently. In addition to business logic, you may also encapsulate platform components and databases as 
   anonymous functions.
2. Event driven programming - Each function is addressable with a unique "route name" and they communicate by 
   sending events. You make request from one function to another by making a service call through some simple 
   Mercury Post Office API.
3. One or more functions can be packaged together as a microservice executable, usually deployed as a Docker 
   image or similar container technology.

`rest-spring`

The rest-spring library customizes and simplifies the use of Spring Boot as an application container to hold functions. 
This includes preconfigured message serializers and exception handlers.

`cloud-connector` and `service-monitor`

The cloud-connector and service-monitor are the core libraries to support implementations of various cloud connectors.

Mercury supports Kafka, Hazelcast, ActiveMQ-artemis and TIBCO-EMS out of the box. 

`kafka-connector`

This is the kafka specific connector library is designed to work with Kafka version 2.7.0 out of the box
that you add it into your application's pom.xml. A convenient standalone Kafka server application is available 
in the `kafka-standalone` project under the `connector/kafka` directory. 
The standalone Kafka server is a convenient tool for application development and tests in the developer's laptop.

`kafka-presence`

This "presence monitor" manages mapping of Kafka topics and detects when an application instance is online or offline. 
Your application instances will report to the presence monitors (2 monitor instances are enough for 
large installations). Since version 2.0, it uses a single partition of a topic to serve an application instance.
To avoid deleting topics, the system can re-use topics and partitions automatically.

The kafka-presence system is fully scalable.

`language-connector`

The python language pack is available in https://github.com/Accenture/mercury-python

`distributed-tracer`

This extension package is an example application that consolidates distributed trace metrics.

`lambda-example`

This is an example project to illustrate writing microservices without any HTTP application server.

`rest-example`

If your application module needs info and health admin endpoints, you may want to use this example project as a 
template.

It encapsulates Spring Boot, automates JSON/XML/PoJo serialization and simplfies application development. 
It also allows you to write custom REST endpoints using WebServlet and JAX-RS REST annotations.

`rest-automation`

For rapid application development, you may use the built-in REST automation system to declare REST endpoints without
coding. The rest-automation application in the 'example' subprojects may be used as template to get started.

## Before you start

If you haven't already, please start a terminal and clone the repository: 
```
git clone https://github.com/Accenture/mercury.git
cd mercury
```

To get the system up and running, you should compile and build the foundation libraries from sources. 
This will install the libraries into your ".m2/repository/org/platformlambda" folder.

`Important` - please close all Java applications and web servers in your PC if any. The build process will invoke unit tests that simulate HTTP and websocket servers.

```bash
# start a terminal and go to the mercury sandbox folder
mvn clean install
```

The platform-core, rest-spring, hazelcast-connector and kafka-connector are libraries and you can rebuild each one 
individually using `mvn clean install`

The rest of the subprojects are executables that you can rebuild each one with `mvn clean package`.

## Getting started

You can compile the rest-example as a microservices executable like this:

```bash
cd mercury/examples
cd rest-example
mvn clean package
java -jar target/rest-example-2.8.0.jar
# this will run the rest-example without a cloud connector
```

Try http://127.0.0.1:8083/api/hello/world with a browser.

It will respond with some sample output like this:

```json
{
  "body" : {
    "body" : {
      "accept" : "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
      "accept-encoding" : "gzip, deflate, br",
      "accept-language" : "en-US,en;q=0.9",
      "cache-control" : "max-age=0",
      "connection" : "keep-alive",
      "host" : "127.0.0.1:8083",
      "time" : "2018-12-21T16:39:33.546Z",
      "upgrade-insecure-requests" : "1",
      "user-agent" : "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) ..."
    },
    "headers" : {
      "seq" : "4"
    },
    "instance" : 4,
    "origin" : "2018122170c4d0e80ef94b459763636da74d6b5f"
  },
  "execution_time" : 0.298,
  "headers" : { },
  "round_trip" : 0.862,
  "status" : 200
}
```

The REST endpoint makes a request to the "hello.world" service that echoes back the headers of the HTTP request. 
The underlying Spring Boot HTTP server has been pre-configured with HTML, JSON and XML message serializers and 
exception handlers.

If you change the "accept" header to "application/xml", the output will be shown in XML.

To demonstrate concurrent processing, you may visit http://127.0.0.1:8083/api/hello/concurrent

Now try http://127.0.0.1:8083/api/hello/pojo/1

This will return a HTTP-500 "Route hello.pojo not found" error. This is expected behavior because the "hello.pojo" 
service is not available in the rest-example container. Let's walk thru distributed and scalable application in 
the next section.

### Scalable application using Kafka

For development and testing, you can start a standalone Kafka server.

```bash
# start a terminal and go to the mercury sandbox folder, then go to the kafka-standalone folder
cd mercury/connectors/kafka/kafka-standalone/
mvn clean package
java -jar target/kafka-standalone-2.8.0.jar
# this will run a standalone Kafka server in the foreground
```

The next step is to start the "presence-monitor" application.

```bash
# start another terminal and go to kafka-presence folder
cd mercury/connectors/kafka/kafka-presence/
mvn clean package
java -jar target/kafka-presence-2.8.0.jar
# this will run the presence monitor at port 8080 in the foreground

# when an application instance is started, it connects to the presence monitor to get topic.
# you will see log similar to the following:
Adding member 20210405aa0220674e404169a5ec158a99714da6
Monitor (me) 20210405209f8e20ed3f4c0a80b035a50273b922 begins RSVP
multiplex.0001-001 assigned to 20210405aa0220674e404169a5ec158a99714da6 lambda-example, 2.1.1
Monitor (me) 20210405209f8e20ed3f4c0a80b035a50273b922 finished RSVP
```

Optionally, if you want to test resilience of the presence monitor, you can start another instance like this:
```bash
# start another terminal and go to kafka-presence folder
cd mercury/connectors/kafka/kafka-presence/
mvn clean package
java -Dserver.port=8081 -jar target/kafka-presence-2.8.0.jar
# this will run the presence monitor at port 8081 in the foreground
```

- You can then run the lambda-example and rest-example applications

```bash
# go to the lambda-example project folder in one terminal
java -Dcloud.connector=kafka -jar target/lambda-example-2.8.0.jar
# the lambda-example will connect to the "presence monitor", obtain a topic and connect to Kafka

# go to the rest-example project folder in another terminal
java -Dcloud.connector=kafka -jar target/rest-example-2.8.0.jar
# the rest-example will also connect to the "presence monitor", obtain a topic and connect to Kafka

# the lambda-example and rest-example apps will show the topic assignment like this
presence.monitor, partition 1 ready
multiplex.0001, partition 0 ready

```

You may visit http://127.0.0.1:8083/api/hello/pojo/1 with a browser, you will see output like this:

```json
{
  "id": 1,
  "name": "Simple PoJo class",
  "address": "100 World Blvd, Planet Earth",
  "date": "2021-04-05T22:13:38.351Z",
  "instance": 1,
  "origin": "20210405aa0220674e404169a5ec158a99714da6"
}
```

This means the rest-example app accepts the HTTP request and sends the request event to the lambda-example app which 
in turns return the response event as shown above.

The "cloud.connector=kafka" parameter overrides the application.properties in the rest-example and lambda-example 
projects so they can select Kafka as the service mesh.

If you use Jetbrains Idea ("IntelliJ") to run your project, please edit the start up config, click 'Modify options' and tick 'add VM options'. This allows you to set default run-time parameters. e.g. "-Dcloud.connector=kafka". Eclipse and other IDE would have similar run-time parameter options.

- Get additional info from the presence monitor

You may visit http://127.0.0.1:8080 and select "info". It may look like this:

```json
{
  "app": {
    "name": "kafka-presence",
    "description": "Presence Monitor",
    "version": "2.1.1"
  },
  "memory": {
    "max": "8,524,922,880",
    "free": "470,420,400",
    "allocated": "534,773,760"
  },
  "personality": "RESOURCES",
  "additional_info": {
    "total": {
      "virtual_topics": 3,
      "topics": 2,
      "connections": 3
    },
    "virtual_topics": [
      "multiplex.0001-000 -> 2021082260f0b955a9ad427db14a9db751340d61, rest-example v2.1.1",
      "multiplex.0001-001 -> 20210822371949df090644428cd98ae0ba91a69b, lambda-example v2.1.1",
      "multiplex.0001-002 -> 2021082295c030edc07a4a4eb5cb78b6421f5fb7, lambda-example v2.1.1"
    ],
    "topics": [
      "multiplex.0001 (32)",
      "service.monitor (11)"
    ],
    "connections": [
      {
        "created": "2021-08-22T17:29:45Z",
        "origin": "20210822371949df090644428cd98ae0ba91a69b",
        "name": "lambda-example",
        "topic": "multiplex.0001-001",
        "monitor": "20210822c750f194403241c49ef8fae113beff75",
        "type": "APP",
        "updated": "2021-08-22T17:29:53Z",
        "version": "2.1.1",
        "seq": 2,
        "group": 1
      },
      {
        "created": "2021-08-22T17:29:55Z",
        "origin": "2021082295c030edc07a4a4eb5cb78b6421f5fb7",
        "name": "lambda-example",
        "topic": "multiplex.0001-002",
        "monitor": "20210822c750f194403241c49ef8fae113beff75",
        "type": "APP",
        "updated": "2021-08-22T17:30:04Z",
        "version": "2.1.1",
        "seq": 2,
        "group": 1
      },
      {
        "created": "2021-08-22T17:29:30Z",
        "origin": "2021082260f0b955a9ad427db14a9db751340d61",
        "name": "rest-example",
        "topic": "multiplex.0001-000",
        "monitor": "20210822c750f194403241c49ef8fae113beff75",
        "type": "WEB",
        "updated": "2021-08-22T17:30:01Z",
        "version": "2.1.1",
        "seq": 3,
        "group": 1
      }
    ],
    "monitors": [
      "20210822c750f194403241c49ef8fae113beff75 - 2021-08-22T17:30:01Z"
    ]
  },
  "vm": {
    "java_vm_version": "11.0.10+9",
    "java_runtime_version": "11.0.10+9",
    "java_version": "11.0.10"
  },
  "streams": {
    "count": 0
  },
  "origin": "20210822c750f194403241c49ef8fae113beff75",
  "time": {
    "current": "2021-08-22T17:30:06.712Z",
    "start": "2021-08-22T17:28:56.128Z"
  }
}
```

## Built-in service mesh

The above demonstrates distributed applications using Kafka as a service mesh.

## Built-in pub/sub

You can also use Mercury with other service mesh of your choice. In this case, you can use the built-in pub/sub APIs 
of Mercury for your app to communicate with Kafka and other event stream systems.

To enable Kafka pub/sub without using it as a service mesh, use these parameters in application.properties
```java
cloud.connector=none
cloud.services=kafka.pubsub
```

This means the system encapsulates the original pub/sub feature of the underlying event stream system. 
The built-in publishers and listeners will do the heavy lifting for you in a consistent manner. Note that Kafka
supports rewinding read "offset" so that your application can read older messages. In Hazelcast, the older events
are dropped after delivery.

Example:
```java
// setup your subscriber function
LambdaFunction myFunction = (headers, body, instance) -> {
  log.info("Got ---> {}", body);
  return true;
};

PubSub ps = PubSub.getInstance();
/*
 * Pub/sub service starts asynchronously.
 * If your runs pub/sub code before the container is completely initialized, 
 * you may want to "waitForProvider" for a few seconds.
 */
ps.waitForProvider(10); 
// this creates a topic with one partition
ps.createTopic("some.kafka.topic", 1); 
// this subscribe the topic with your function
ps.subscribe("some.kafka.topic", myFunction, "client-101", "group-101");
// this publishes an event to the topic
ps.publish("some.kafka.topic", null, "my test message");
```

If you run this application for the first time and you do not see the test message, the kafka topic has just been 
created  when your application starts. Due to racing condition, Kafka would skip the offset and you cannot see the
first message. Just restart the application and you will see your test message.

However, if you create the topic administratively before running this test app, your app will always show the first 
test message. This is a normal Kafka behavior.

You may also notice that the Kafka client sets the read offset to the latest pointer. To read from the beginning, 
you may reset the read offset by adding a parameter "0" after the clientId and groupId in the subscribe statement above.

## Work nicely with reactive frameworks

Mercury provides a stream abstraction that can be used with reactive frameworks.

For example, developers using Spring reactor with Mercury may setup a stream between two app modules 
within the same container or in different containers like this:

```java
// at the producer app container
ObjectStreamIO producer = new ObjectStreamIO(TIMEOUT_IN_SECONDS);
ObjectStreamWriter out = producer.getOutputStream();
out.write("hello"); // you can send text, bytes, Map or PoJo
out.write("world");
out.close(); // to indicate end-of-stream
        
String streamId = producer.getRoute();
// deliver the streamId to the consumer using PostOffice
//
// at the consumer app container
ObjectStreamIO consumer = new ObjectStreamIO(streamId);
ObjectStreamReader in = consumer.getInputStream(TIMEOUT_IN_MILLISECONDS);
Flux.fromIterable(in).log()
    .doOnNext((d) -> {
        // handle data block
    }).doOnError((e) -> {
        // handle exception
    }).doOnComplete(() -> {
        // handle completion
        in.close(); // close I/O stream
    }).subscribeOn(Schedulers.parallel()).subscribe();
```

## Write your own microservices

You may use the lambda-example and rest-example as templates to write your own applications.

Please update pom.xml and application.properties for application name accordingly.

## Cloud Native

The Mercury framework is Cloud Native. While it uses the local file system for buffering, it expects local storage 
to be transient.

If your application needs to use the local file system, please consider it to be transient too, i.e., you cannot 
rely on it to persist when your application restarts.

If there is a need for data persistence, use external databases or cloud storage.

## Dockerfile

Creating a docker image from the executable is easy. First you need to build your application as an executable 
with the command `mvn clean package`. The executable JAR is then available in the target directory.

The Dockerfile may look like this:

```bash
FROM adoptopenjdk/openjdk11:jre-11.0.11_9-alpine
EXPOSE 8083
WORKDIR /app
COPY target/your-app-name.jar .
ENTRYPOINT ["java","-jar","your-app-name.jar"]

```

To build a new docker image locally:
```
docker build -t your-app-name:latest .
```

To run the newly built docker image, try this:
```
docker run -p 8083:8083 -i -t your-app-name:latest
```

Change the exposed port numnber and application name accordingly. Then build the docker image and publish it to a 
docker registry so you can deploy from there using Kubernetes or alike.

For security reason, you may want to customize the docker file to use non-priliveged Unix user account. Please 
consult your company's enterprise architecture team for container management policy.

## VM or bare metal deployment

If you are deploying the application executables in a VM or bare metal, we recommend using a cross-platform process
manager. The system has been tested with "pm2" (https://www.npmjs.com/package/pm2).

A sample process.json file is shown below. Please edit the file accordingly. You may add "-D" or "-X" parameters 
before the "-jar" parameter. To start the application executable, please do `pm2 start my-app.json`.

You may create individual process.json for each executable and start them one-by-one. You can then monitor the 
processes with `pm2 list` or `pm2 monit`.

To deploy using pm2, please browse the pm2-example folder as a starting point.

## Distributed tracing

Microservices are likely to be deployed in a multi-tier environment. As a result, a single transaction may pass 
through multiple services.

Distributed tracing allows us to visualize the complete service path for each transaction. This enables easy 
trouble shooting for large scale applications.

With the Mercury framework, distributed tracing does not require code at application level. To enable this feature, 
you can simply set "tracing=true" in the rest.yaml configuration of the rest-automation application.

## JDK compatibility

The Mercury project has been tested with OpenJDK and AdaptOpenJDK version 8 to 18.

Please use Java version 11 or higher to run the kafka-standalone application which is provided as a convenient tool for
development and testing. The kafka standalone server would fail due to a known memory mapping bug when running under Java version 1.8.0_292.

## Kafka compatibility

As of December 2020, Mercury has been tested and deployed with Apache Kafka, Confluent Kafka, IBM Event Streams 
and Microsoft Azure Event Hubs.

## Other consideration

- Timestamp: the Mercury system uses UTC time and ISO-8601 string representation when doing serialization. 
  https://en.wikipedia.org/wiki/ISO_8601

- UTF8 text encoding: we recommend the use of UTF8 for text strings.


## Developer guide

For more details, please refer to the [Developer Guide](guides/TABLE-OF-CONTENTS.md)
