﻿# Post Office API

Post Office is a platform abstraction layer that routes events among functions. It maintains a distributed 
routing table to ensure that service discovery is instantaneous,

## Obtain an instance of the post office object

```
PostOffice po = PostOffice.getInstance();
```

## Communication patterns

- RPC `“Request-response”, best for interactivity`
- Asynchronous `e.g. Drop-n-forget and long queries`
- Call-back `e.g. Progressive rendering`
- Pipeline `e.g. Work-flow application`
- Streaming `e.g. Data ingest`
- Broadcast `e.g. Concurrent processing of the same dataset with different outcomes`

### RPC (Request-response)

The Mercury framework is 100% event-driven and all communications are asynchronous. 
To emulate a synchronous RPC, it uses temporary Inbox as a callback function. 
The target service will send reply to the callback function which in turns delivers the response.

To make an RPC call, you can use the `request` methods. These methods would throw exception if the response 
status is not 200.

```java
EventEnvelope request(String to, long timeout, Object body) throws IOException, TimeoutException, AppException;
EventEnvelope request(String to, long timeout, Object body, Kv... parameters) throws IOException, TimeoutException, AppException;
EventEnvelope request(EventEnvelope event, long timeout) throws IOException, TimeoutException, AppException;

// example
EventEnvelope response = po.request("hello.world", 1000, somePayload);
System.out.println("I got response..."+response.getBody());

```

A non-blocking version of RPC is available with the `asyncRequest` method. Only timeout exception will be sent
to the onFailure method. All other cases will be delivered to the onSuccess method. You should check event.getStatus()
to handle exception.

```java
Future<EventEnvelope> asyncRequest(final EventEnvelope event, long timeout) throws IOException;

// example
Future<EventEnvelope> future = po.asyncRequest(new EventEnvelope().setTo(SERVICE).setBody(TEXT), 2000);
future.onSuccess(event -> {
    // handle the response event
}).onFailure(ex -> {
    // handle timeout exception
});
```

Note that Mercury supports Java primitive, Map and PoJo in the message body. 
If you put other object, it may throw serialization exception or the object may become empty.

### Asynchronous / Drop-n-forget

To make an asynchronous call, use the `send` method.

```java
void send(String to, Kv... parameters) throws IOException;
void send(String to, Object body) throws IOException;
void send(String to, Object body, Kv... parameters) throws IOException;
void send(final EventEnvelope event) throws IOException;

```
Kv is a key-value pair for holding one parameter.

### Deferred delivery

```java
String sendLater(final EventEnvelope event, Date future) throws IOException;
```
A scheduled ID will be returned. You may cancel the scheduled delivery with `cancelFutureEvent(id)`.

### Call-back

You can register a call back function and uses its route name as the "reply-to" address in the send method. 
To set a reply-to address, you need to use the EventEnvelope directly.

```java
void send(final EventEnvelope event) throws IOException;

// example
EventEnvelope event = new EventEnvelope();
event.setTo("hello.world").setBody(somePayload);
po.send(event);
```

To handle exception from a target service, you may implement ServiceExceptionHandler. For example:

```java
private static class SimpleCallback implements TypedLambdaFunction<PoJo, Void>, ServiceExceptionHandler {
    
    @Override
    public void onError(AppException e, EventEnvelope event) {
        // handle exception here
    }

    @Override
    public Void handleEvent(Map<String, String> headers, PoJo body, int instance) throws Exception {
        // handle input. In this example, it is a PoJo
        return null;
    }
}
```

### Pipeline

In a pipeline operation, there is stepwise event propagation. e.g. Function A sends to B and set the "reply-to" as C. 
Function B sends to C and set the "reply-to" as D, etc.

To pass a list of stepwise targets, you may send the list as a parameter. Each function of the pipeline should forward 
the pipeline list to the next function.

```java
EventEnvelope event = new EventEnvelope();
event.setTo("function.b").setBody(somePayload).setReplyTo("function.c")
     .setHeader("pipeline",  "function.a->function.b->function.c->function.d");
po.send(event);
```

### Streaming

You can use streams for functional programming. There are two ways to do streaming.

1. Singleton functions

To create a singleton, you can set `instances` of the calling and called functions to 1. 
When you send events from the calling function to the called function, the platform guarantees 
that the event sequencing of the data stream.

To guarantee that there is only one instance of the calling and called function, you should register 
them with a globally unique route name. e.g. using UUID like "producer-b351e7df-827f-449c-904f-a80f9f3ecafe" 
and "consumer-d15b639a-44d9-4bc2-bb54-79db4f866fe3".

Note that you can programmatically `register` and `release` a function at run-time.

If you create the functions at run-time, please remember to release the functions when processing is completed to 
avoid wasting system resources.

2. Object stream

To do object streaming, you can use the ObjectStreamIO to create a stream or open an existing stream.
Then, you can use the `ObjectStreamWriter` and the `ObjectStreamReader` classes to write to and read from the stream.

For the producer, if you close the output stream, the system will send a `EOF` to signal that there are no more 
events to the stream. 

For the consumer, When you detect the end of stream, you can close the input stream to release the stream and all 
resources associated with it.

I/O stream consumes resources and thus you must close the input stream at the end of stream processing.
The system will automatically close the stream upon an expiry timer that you provide when a new stream is created.

The following unit test demonstrates this use case.

```java
String messageOne = "hello world";
String messageTwo = "it is great";
/*
 * Producer creates a new stream with 60 seconds inactivity expiry
 */
ObjectStreamIO stream = new ObjectStreamIO(60);
ObjectStreamWriter out = new ObjectStreamWriter(stream.getOutputStreamId());
out.write(messageOne);
out.write(messageTwo);
/*
 * If output stream is closed, it will send an EOF signal so that the input stream reader will detect it.
 * Otherwise, input stream reader will see a RuntimeException of timeout.
 *
 * For this test, we do not close the output stream to demonstrate the timeout.
 */
//  out.close();

/*
 * Producer should send the inputStreamId to the consumer using "stream.getInputStreamId()" after the stream is created.
 * The consumer can then open the stream with the streamId.
 */
ObjectStreamReader in = new ObjectStreamReader(inputStreamId, 8000);
int i = 0;
try {
    for (Object data : in) {
        if (data == null) break; // EOF
        i++;
        if (i == 1) {
            assertEquals(messageOne, data);
        }
        if (i == 2) {
            assertEquals(messageTwo, data);
        }
    }
} catch (RuntimeException e) {
    // iterator will timeout since the stream was not closed
    assertTrue(e.getMessage().contains("timeout"));
}

// ensure that it has read the two messages
assertEquals(2, i);
// must close input stream to release resources
in.close();
```

### Broadcast

Broadcast is the easiest way to do "pub/sub". To broadcast an event to multiple application instances, 
use the `broadcast` method.

```java
void broadcast(String to, Kv... parameters) throws IOException;
void broadcast(String to, Object body) throws IOException;
void broadcast(String to, Object body, Kv... parameters) throws IOException;

// example
po.broadcast("hello.world", "hey, this is a broadcast message to all hello.world providers");

```

### Fork-n-Join

You can perform fork-n-join RPC calls using a parallel version of the `request` method.

```java
List<EventEnvelope> request(List<EventEnvelope> events, long timeout) throws IOException;

// example
List<EventEnvelope> parallelEvents = new ArrayList<>();

EventEnvelope event1 = new EventEnvelope();
event1.setTo("hello.world.1");
event1.setBody(payload1);
event1.setHeader("request", "#1");
parallelEvents.add(event1);

EventEnvelope event2 = new EventEnvelope();
event2.setTo("hello.world.2");
event2.setBody(payload2);
event2.setHeader("request", "#2");
parallelEvents.add(event2);

List<EventEnvelope> responses = po.request(parallelEvents, 3000);
```

A non-blocking version of fork-n-join is available with the `asyncRequest` method. Only timeout exception will be sent
to the onFailure method. All other cases will be delivered to the onSuccess method. You should check event.getStatus()
to handle exception.

```java
Future<List<EventEnvelope>> asyncRequest(final List<EventEnvelope> event, long timeout) throws IOException;

// example
List<EventEnvelope> requests = new ArrayList<>();
requests.add(new EventEnvelope().setTo(SERVICE1).setBody(TEXT1));
requests.add(new EventEnvelope().setTo(SERVICE2).setBody(TEXT2));
Future<List<EventEnvelope>> future = po.asyncRequest(requests, 2000);
future.onSuccess(events -> {
    // handle the response events
}).onFailure(ex -> {
    // handle timeout exception
});
```

### Inspecting event's metadata

If you want to inspect the incoming event's metadata to make some decisions such as checking correlation-ID and 
sender's route address, you can use the TypedLambdaFunction to specify input as EventEnvelope.

Another way to inspect event's metadata is the use of the `EventInterceptor` annotation in your lambda function. 
Note that event interceptor service does not return result, it intercepts incoming event for forwarding to one or 
more target services. If the incoming request is a RPC call and the interceptor does not forward the event to the 
target service, the call will time out.

### Default PoJo mapping

PoJo mapping is determined at the source. When the caller function sets the PoJo, the object is restored as the 
original PoJo in the target service provided that the common data model is available in both source and target services.

```java
public Object getBody(); // <-- default mapping
```

### Retrieve raw data as a Map

```java
public Object getRawBody();
```

### Custom PoJo mapping

In case you want to do custom mapping, the platform will carry out best effort mapping from the source PoJo to the 
target PoJo. You must ensure the target object is compatible with the source PoJo. Otherwise, there will be data lost 
or casting error.

```java
public <T> T getBody(Class<T> toValueType); // <-- custom mapping
public <T> T getBody(Class<T> toValueType, Class<?>... parameterClass); // custom generics
```

### Check if a target service is available

To check if a target service is available, you can use the `exists` method.

```java
boolean po.exists(String... route);

if (po.exists("hello.world")) {
    // do something
}

if (po.exists("hello.math", "v1.diff.equation")) {
    // do other things
}
```
This service discovery process is instantaneous using distributed routing table.

### Get a list of application instances that provide a certain service

The `search` method is usually used for leader election for a certain service
if more than one app instance is available.

```java
List<String> originIDs = po.search(String route);
```

### Pub/Sub for store-n-forward event streaming

Mercury provides real-time inter-service event streaming and you do not need to deal with low-level messaging.

However, if you want to do store-n-forward pub/sub for certain use cases, you may use the `PubSub` class.

In event streaming, pub/sub refers to the long term storage of events for event playback or "time rewind".
For example, this "commit log" architecture is available in Apache Kafka.

To test if the underlying event system supports pub/sub, use the "isStreamingPubSub" API.

Following are some useful pub/sub API:

```java
public boolean featureEnabled();
public boolean createTopic(String topic) throws IOException;
public boolean createTopic(String topic, int partitions) throws IOException;
public void deleteTopic(String topic) throws IOException;
public void publish(String topic, Map<String, String> headers, Object body) throws IOException;
public void publish(String topic, int partition, Map<String, String> headers, Object body) throws IOException;
public void subscribe(String topic, LambdaFunction listener, String... parameters) throws IOException;
public void subscribe(String topic, int partition, LambdaFunction listener, String... parameters) throws IOException;
public void unsubscribe(String topic) throws IOException;
public void unsubscribe(String topic, int partition) throws IOException;
public boolean exists(String topic) throws IOException;
public int partitionCount(String topic) throws IOException;
public List<String> list() throws IOException;
public boolean isStreamingPubSub();
```
Some pub/sub engine would require additional parameters when subscribing a topic. For Kafka, you must provide the 
following parameters

1. clientId
2. groupId
3. optional read offset pointer

If the offset pointer is not given, Kafka will position the read pointer to the latest when the clientId and groupId are 
first seen. Thereafter, Kafka will remember the read pointer for the groupId and resume read from the last read pointer.

As a result, for proper subscription, you must create the topic first and then create a lambda function to subscribe to 
the topic before publishing anything to the topic.

To read the event stream of a topic from the beginning, you can set offset to "0".

The system encapsulates the headers and body (aka payload) in an event envelope so that you do not need to do 
serialization yourself. The payload can be PoJo, Map or Java primitives.

### Thread safety

The "handleEvent" is the event handler for each LamdbaFunction. When you register more than one worker instance for
your function, please ensure that you use "functional scope" variables instead of global scope variables.

If you must use global scope variables, please use Java Concurrent collections.

### Exception handling

When your lambda function throws exception, the exception will be encapsulated in the result event envelope to
the calling function.

If your calling function uses RPC, the exception will be caught as an AppException. You can then use the exception's
`getCause()` method to retrieve the original exception chain from the called function.

If your calling function uses CALLBACK pattern, your callback function can inspect the incoming event envelope and
use the `getException()` method to obtain the original exception chain from the called function.


---

| Chapter-4                                 | Home                                     |
| :----------------------------------------:|:----------------------------------------:|
| [REST automation](CHAPTER-4.md)           | [Table of Contents](TABLE-OF-CONTENTS.md)|
