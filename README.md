# Asterisk Call Center Integration Library

The main purpose of the library is to connect to an Asterisk PBX server and provide filtered event streams that allow the receiver to track the state of call queues and agents. Additional features include originating calls and managing the status of agents against specific queues (log on/off, pause). Events are received over a [*lamina*](https://github.com/ztellman/lamina) channel, which makes [*aleph*](https://github.com/ztellman/aleph) a particularly convenient library to use when implementing a server on top of this library, but there is no restriction to it.

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

`originate-call`: place a phone call from a source.

`queue-action`: manage agent status with respect to a particular agent queue.


## Short intro to terminology

Asterisk provides specific support for the management of phone calls received by an incoming call center. The calls accumulate in a named *agent queue* (or just *queue*) and there can be more such queues. In a simple scenario a single queue will map to a single public phone number. A *queue count* is the current number of incoming calls waiting in a queue.

An *agent* can be registered as a *member* of a queue, which means calls will be forwarded to his phone. The agent is represented in the system by his/her phone number. The term *extension* is often used to mean either the phone device or the phone number of that device.

PBXIS defines an *event channel* over which events are received by the client. The event channel is configured with two lists used to filter the events delivered to it:

1. a list of *extensions*;
2. a list of *queues*.

Depending on its type, an event may have the properties `:agent` and/or `:queue`, called the *scope properties*. An event channel will receive an event iff the value of each scope property of the event exists in the corresponding list in the configuration of the event channel. So an event with neither property is received by all channels and an event with both properties is received only by channels that include both the agent and the queue in their configuration.


## Reported Events

Events, enqueued into the event channel, are maps where the key `:type` determines event type, the keys `:agent` and `:queue` determine its scope, and the rest are event details. List of events by type:

**`extensionStatus`**: status of a phone device. Scope: `:agent`. Detail: `:status`, one of:

- `not_inuse`: the phone is idle and ready to accept calls;

- `ringing`: the phone is on-hook and ringing;

- `inuse`: the phone is off the hook;

- `ringinuse`: the phone is in use and there's another incoming call waiting;

- `busy`: the phone is in use and there's another incoming call, which will receive a busy signal;

- `onhold`: there is an active call that was put on hold;

- `unavailable`: the phone is not available to Asterisk (it's off-line).


**`queueMemberStatus`**: status of the agent with respect to a particular agent queue. Scope: `:agent`, `:queue`. Detail: `:status`, one of:

- `loggedoff`: the agent is not logged on to this queue;

- `paused`: the agent is logged on, but *paused*, meaning calls will not be forwarded to him;

- `not_inuse`, `inuse`, `busy`, `ringing`, `ringinuse`, `onhold`, `unavailable`: the agent is logged on and this is the detailed status. The meanings are the same as in `extensionStatus`, but the status is updated only after logging in and when there is a new call in the queue.

- `unknown`.


**`phoneNumber`**: phone number of the remote party currently patched through to agent's extension. Scope: `:agent`. Detail: `:number`, the phone number (string) or `nil` if there is no call; `:name`, the party name (if available).

**`queueCount`**: number of callers waiting in an agent queue. Scope: `:queue`. Detail: `:count`, the queue count.

**`agentComplete`**: contains summary info on the just-completed agent call. Scope: `:agent`. Detail: `:uniqueId`, unique ID of the call (string); `:talkTime`, talk time in seconds (integer); `:holdTime`, hold time in seconds (integer); `:recording`, path to the recorded call on the server (string).

**`originateFailed`**: when an `originate-call` request failed. Scope: none. Detail: `:actionId`, ID of the request, as previously returned by `originate-call`.



## License

The software is licensed under the Apache License, Version 2.0.