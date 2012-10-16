# Asterisk Call Center Integration Library

The library connects to an Asterisk server that manages a call center and provides a stream of interesting Asterisk events through an easily accessible API. It supports both synchronous (blocking) and asynchronous (callback) mode of operation. The library additionally supports issuing commands to Asterisk, such as originating a call and managing agent status.

The API is as follows:

`ami-connect`: connect to the asterisk server.

`ami-disconnect`: disconnect from the server and release all resources.

While connected, these functions are supported:

`config-agnt`: register a call-center agent with the names of Asterisk queues the agent is a member of. This function accepts an optional list of event listener functions.

`events-for`: synchronously gather events for a registered agent. The function blocks until an event occurs within the timeout period.

`originate-call`: place a phone call to the supplied phone number and patch it through to agent's extension.

`queue-action`: manage agent status with respect to a particular agent queue.


## License

The software is licensed under the Apache License, Version 2.0.