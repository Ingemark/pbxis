# Asterisk Call Center Integration Library

The main purpose of the library is to connect to an Asterisk PBX server and provide filtered event streams that allow the receiver to track the state of call queues and agents. Additional features include originating calls and managing the status of agents against specific queues (log on/off, pause). Events are received over a *lamina* channel, which makes *aleph* a particularly convenient library to use when implementing a server on top of this library, but there is no restriction to it.

The library can be used to implemented a scenario like the following:

* there is a web application that agents use in their workplace;
* *pbxis* is itself exposed as a web service;
* the web application subscribes an agent to the *pbxis* service and passes the event-stream URL to the agent's browser;
* the browser contacts *pbxis* directly to receive the event stream.

Another use case is a supervisor application that monitors the activities of all the agents in the call center. The `examples/http` directory contains a very simple example of such an application.


## Feature highlights

* connects to Asterisk via the Asterisk Manager Interface (AMI);

* provides a stream of agent-related events through an easily accessible API;

* raw AMI events are collated and digested into a model that minimizes client-side processing;

* can issue commands to Asterisk, such as originating a call or managing agent status;

* robust to AMI connection failures: automatically reconnects and restores the tracked state;

* uses a single AMI connection to cater for all registered agents;

* keeps the traffic over the AMI connection to a bare minimum:
  * sets up an appropriate event filter on the Asterisk side;
  * keeps track of all state through the event stream (doesn't poll the AMI);
  * tailors AMI actions to generate the necessary minimum of traffic when regenerating state.

## API overview

`ami-connect`: connect to the asterisk server.

`ami-disconnect`: disconnect from the server and release all resources.

While connected, these functions are supported:

`event-channel`: returns a lamina channel over which the client receives the event stream.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## Reported Events

Events, enqueued into the event sink channel, are maps where the key :type determines event type and the rest are event details.

`queueMemberStatus`: status of the agent with respect to a particular agent queue. Detail: `:queue`, the queue name; `:status`, one of `#{"unknown", "not_inuse", "inuse", "busy", "unavailable", "ringing", "ringinuse", "onhold"}`.

`queueCount`: number of callers waiting in an agent queue. Detail: `:queue`, the queue name; `:count`, the queue count.

`extensionStatus`: status of the agent's extension. Detail: `:status`, one of `#{"not_inuse", "inuse", "busy", "unavailable", "ringing", "onhold"}`.

`phoneNumber`: phone number of the remote party currently patched through to agent's extension. Detail:`:number`, the phone number (string); `:name`, the party name (if available).

`agentComplete`: contains summary info on the just-completed agent call. Detail: `:uniqueId`, unique ID of the call (string); `:talkTime`, talk time in seconds (integer); `:holdTime`, hold time in seconds (integer); `:recording`, path to the recorded call on the server (string).

`originateFailed`: when an `originate-call` request failed. Detail: `:actionId`, ID of the request, as previously returned by `originate-call`.


## Examples

The `examples/http` project exposes the library functions as a RESTful web service and can provide the event stream directly to a web browser over HTTP long polling, Server-Sent Events, and Websockets. The project also includes a ready-to-test HTML page which displays the status of an arbitrary number of agents and queues. The relative URL of the page is `/client/<tech>/<agents>/<queues>`, where <tech> is one of "long-poll", "sse", or "websocket", <agents> is a comma-separated list of agents' extensions, and <queues> is a comma-separated list of queue names.


## License

The software is licensed under the Apache License, Version 2.0.