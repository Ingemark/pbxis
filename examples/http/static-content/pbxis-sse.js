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
                eventSource.onopen = function() { pbx_connection(true); }
                $.each(["queueMemberStatus","extensionStatus","queueCount","phoneNumber","closed"],
                       function(_, t) { eventSource.addEventListener(t, function (e) {
                           //console.log("SSE event " + e.data);
                           var ev = JSON.parse(e.data);
                           ev.type = e.type;
                           if (!handle_event(ev)) {
                               console.log("Close eventSource");
                               eventSource.close();
                           }
                       }
                                                                    ); });
            }
        }
    )
}
