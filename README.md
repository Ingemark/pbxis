# Asterisk Call Center Integration Library

The library connects to a call center's Asterisk server and exposes services typically needed by an application that agents use while serving the customer calls. Allows easy integration of Asterisk-related services into the wider scope, such as a CRM application.

## Feature highlights

* connects to Asterisk via the Asterisk Manager Interface (AMI);

* provides a stream of agent-related events through an easily accessible API;

* raw AMI events are digested into a model that minimizes client-side processing;

* supports both synchronous (blocking) and asynchronous (callback-based) reception of events;

* can issue commands to Asterisk, such as originating a call or managing agent status;

* robust to connection failures: automatically reconnects and restores the tracked state;

* uses a single AMI connection to cater for all registered agents;

* keeps the traffic over the AMI connection to a bare minimum:
  * sets up an appropriate event filter on the Asterisk side;
  * keeps track of all state through the event stream (doesn't poll the AMI);
  * tailors AMI actions to generate the necessary minimum of traffic when regenerating state.

## API overview

`ami-connect`: connect to the asterisk server.

`ami-disconnect`: disconnect from the server and release all resources.

While connected, these functions are supported:

`config-agnt`: register a call-center agent with the names of Asterisk queues the agent is a member of. This function accepts an optional list of event listener functions.

`events-for`: synchronously gather events for a registered agent. The function blocks until an event occurs within the timeout period.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## Reported Events

`queueMemberStatus`: status of the agent with respect to a particular agent queue (logged off, logged on, paused).

`queueCount`: number of callers waiting in an agent queue.

`extensionStatus`: status of the agent's extension (available, in use, busy, ringing, on hold, ...).

`phoneNum`: phone number of the remote party currently patched through to agent's extension.

`agentComplete`: contains summary info on the just-completed agent call.

`placeCallFailed`: when an originate-call request failed.


## Examples

The `examples` directory contains a project `http` which implements a simple Ring HTTP server that exposes *pbxis* functions as a lightweight RESTful service. Events can be collected using long polling. The server also provides an HTML homepage which uses JavaScript to connect to the event stream and updates the page with the current status of a call-center agent.


## License

The software is licensed under the Apache License, Version 2.0.