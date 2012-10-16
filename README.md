# Asterisk Call Center Integration Library

The library connects to an Asterisk server that manages a call center and exposes services that are required to implement an application used by agents serving the phone calls. Allows easy integration of Asterisk services into the wider scope, such as a CRM application.

## Feature highlights

* provides a stream of interesting Asterisk events through an easily accessible API;

* supports both synchronous (blocking) and asynchronous (callback) mode of operation;

* supports issuing commands to Asterisk, such as originating a call and managing agent status;

* connects via the Asterisk Manager Interface (AMI);

* upon initial connect, retrieves all current state that is available;

* automatically reconnects to Asterisk upon connection loss and restores the tracked state;

* uses only a single manager connection to Asterisk to cater for all registered agents;

* keeps the traffic over the AMI connections to a bare minimum:
  * activates the event filter on the Asterisk side;
  * keeps track of all state through the event stream (doesn't poll the AMI);
  * uses the AMI actions that will generate the necessary minimum of traffic when regenerating state.


## API overview

`ami-connect`: connect to the asterisk server.

`ami-disconnect`: disconnect from the server and release all resources.

While connected, these functions are supported:

`config-agnt`: register a call-center agent with the names of Asterisk queues the agent is a member of. This function accepts an optional list of event listener functions.

`events-for`: synchronously gather events for a registered agent. The function blocks until an event occurs within the timeout period.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## Events

`queueMemberStatus`: status of the agent with respect to a particular agent queue (logged off, logged on, paused).

`queueCount`: number of callers waiting in an agent queue.

`extensionStatus`: status of the agent's extension (available, in use, busy, ringing, on hold, ...).

`phoneNum`: phone number of the remote party currently patched through to agent's extension.

`agentComplete`: when the agent completes serving a call from the agent queue.

`placeCallFailed`: when an originate-call request failed.


## Examples

The `examples` directory contains a project `http` which implements a simple Ring HTTP server that exposes pbxis functions as a lightweight RESTful service. Events can be collected using long-polling. The server also provides an HTML homepage which uses JavaScript to connect to the event stream and update the page with the current status of a call-center agent.


## License

The software is licensed under the Apache License, Version 2.0.