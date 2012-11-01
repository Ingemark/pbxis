function pbx_start(agent, queues) {
    $.ajax(
        {
            type: "POST",
            url: "/agent/"+agent,
            data: JSON.stringify({queues: queues}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(ticket) {
                eventSource = new EventSource("/agent/"+ticket+"/sse");
                pbx_connection(true);
                eventSource.onopen = function() { pbx_connection(true); }
                eventSource.onmessage = handle_event;
            }
        }
    )
}
