function pbx_start(agent, queues) {
    $.ajax(
        {
            type: "POST",
            url: "/agent/"+agent,
            data: JSON.stringify({queues: queues}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(ticket) {
                var socket = new WebSocket(
                    "ws" + document.location.origin.substring(4) + "/agent/"+ticket+"/websocket");
                socket.onopen = function() { pbx_connection(true); }
                socket.onclose = function() { pbx_connection(false); }
                socket.onmessage = function(e) {
                    e = JSON.parse(e.data);
                    if (!handle_event({"type":e[0], "data":e.slice(1)}))
                        socket.close();
                };
            }
        }
    )
}
