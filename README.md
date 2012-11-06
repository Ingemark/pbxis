# Asterisk Call Center Integration Library

The library connects to a call center's Asterisk server and exposes services typically needed by an application that agents use while serving the customer calls. Allows easy integration of Asterisk-related services into the wider scope, such as a CRM application.

The library is designed with the following scenario in mind (but in no way limited to it):

* there is a web application that agents use in their workplace;
* *pbxis* is itself exposed as a web service;
* the web application subscribes an agent to the *pbxis* service and passes its event-stream URL to the agent's browser;
* the browser contacts *pbxis* directly to receive the event stream.

Events are received through a lamina channel, so it is recommended to implement a server with aleph, although there is no restriction to it.

## Feature highlights

* connects to Asterisk via the Asterisk Manager Interface (AMI);

* provides a stream of agent-related events through an easily accessible API;

* raw AMI events are collated and digested into a model that minimizes client-side processing;

* can issue commands to Asterisk, such as originating a call or managing agent status;

* robust to AMI connection failures: automatically reconnects and restores the tracked state;

* robust to client connection failures: keeps enqueueing events for a configured period within which a client can reconnect and continue receiving events with no loss;

* uses a single AMI connection to cater for all registered agents;

* keeps the traffic over the AMI connection to a bare minimum:
  * sets up an appropriate event filter on the Asterisk side;
  * keeps track of all state through the event stream (doesn't poll the AMI);
  * tailors AMI actions to generate the necessary minimum of traffic when regenerating state.

## API overview

`ami-connect`: connect to the asterisk server.

`ami-disconnect`: disconnect from the server and release all resources.

While connected, these functions are supported:

`config-agnt`: register a call-center agent with the names of Asterisk queues the agent is interested in. This function accepts an optional list of event listener functions.

`attach-sink`: register lamina channel as an event sink that will receive events.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## Reported Events

Events, enqueued into the event sink channel, are maps where the key :type determines event type and the rest are event details.

`queueMemberStatus`: status of the agent with respect to a particular agent queue. Detail: `:queue`, the queue name; `:status`, one of `#{"unknown", "not_inuse", "inuse", "busy", "unavailable", "ringing", "ringinuse", "onhold"}`.

`queueCount`: number of callers waiting in an agent queue. Detail: `:queue`, the queue name; `:count`, the queue count.

`extensionStatus`: status of the agent's extension. Detail: `:status`, one of `#{"not_inuse", "inuse", "busy", "unavailable", "ringing", "onhold"}`.

`phoneNumber`: phone number of the remote party currently patched through to agent's extension. Detail:`:number`, the phone number (string).

`agentComplete`: contains summary info on the just-completed agent call. Detail: `:uniqueId`, unique ID of the call (string); `:talkTime`, talk time in seconds (integer); `:holdTime`, hold time in seconds (integer); `:recording`, path to the recorded call on the server (string).

`originateFailed`: when an `originate-call` request failed. Detail: `:actionId`, ID of the request, as previously returned by `originate-call`.


## Examples

The `examples` directory contains a project `http` which uses aleph to exposes *pbxis* functions as a lightweight RESTful service. Events can be collected using long polling, Server-sent Events, or WebSockets. The server also provides an HTML user interface to try out each of the event-stream technologies. The relative URL is `/client/<tech>/<agent>`, where <tech> is one of long-poll, sse, or websocket, and <agent> is the extension of the agent whose event stream to receive and visualize.


## License

The software is licensed under the Apache License, Version 2.0.