# Asterisk Call Center Integration Library

The library connects to a call center's Asterisk server and exposes services typically needed by an application that agents use while serving the customer calls. Allows easy integration of Asterisk-related services into the wider scope, such as a CRM application.

The library is designed with the following scenario in mind (but in no way limited to it):

* there is a web application that agents use in their workplace;
* *pbxis* is itself exposed as a web service;
* the web application subscribes an agent to the *pbxis* service and passes its event-stream URL to the agent's browser;
* the browser contacts *pbxis* directly to receive the event stream.

Implementations can choose by which mechanism the client will receive the events. In addition to the natural asynchronous model, the library has a blocking function which makes the implementation of long polling trivially easy to achieve.

## Feature highlights

* connects to Asterisk via the Asterisk Manager Interface (AMI);

* provides a stream of agent-related events through an easily accessible API;

* raw AMI events are collated and digested into a model that minimizes client-side processing;

* supports both synchronous (blocking) and asynchronous (callback-based) reception of events;

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

`register-sink`: register an event sink function that will asynchronously receive events.

`events-for`: synchronously gather events for a registered agent.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## Reported Events

Events, as returned to `events-for` and passed to callbacks, are simple vectors where the first member determines event type and the rest are event details.

`queueMemberStatus`: status of the agent with respect to a particular agent queue. Detail: a map `queueName->memberStatus`, where `memberStatus` is one of `#{"unknown", "not_inuse", "inuse", "busy", "unavailable", "ringing", "ringinuse", "onhold"}`. Only updated statuses are sent (the map doesn't usually contain the status for all subscribed queues).

`queueCount`: number of callers waiting in agent queues. Detail: a map `queueName->queueCount`. Only updated counts are sent (the map doesn't usually contain the counts for all subscribed queues).

`extensionStatus`: status of the agent's extension. Detail: a string, one of `#{"not_inuse", "inuse", "busy", "unavailable", "ringing", "onhold"}`.

`phoneNumber`: phone number of the remote party currently patched through to agent's extension. Detail: the phone number (string).

`agentComplete`: contains summary info on the just-completed agent call. Details: unique ID of the call (string), talk time in seconds (integer), hold time in seconds (integer), path to the recorded call on the server (string).

`originateFailed`: when an `originate-call` request failed. Detail: ID of the request, as previously returned by `originate-call`.

`newTicket`: after a new event sink function is registered, it receives a ticket it can use later to reconnect in the case of a failure.


## Examples

The `examples` directory contains a project `http` which implements a simple Ring HTTP server that exposes *pbxis* functions as a lightweight RESTful service. Events can be collected using long polling. The server also provides an HTML homepage which uses JavaScript to connect to the event stream and updates the page with the current status of a call-center agent.


## License

The software is licensed under the Apache License, Version 2.0.