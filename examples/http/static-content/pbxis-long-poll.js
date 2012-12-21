function handle_events(events) {
    var result = true;
    $.each(events, function(_, e) { result &= handle_event(e); });
    return result;
}

function pbx_long_poll(ticket) {
    $.getJSON("/" + ticket + "/long-poll", function(r) {
        if (handle_events(r)) pbx_long_poll(ticket);
        else pbx_connection(false);
    }).error(function() {pbx_connection(false)});
}

function pbx_start(agents, queues) {
    $.ajax(
        {
            type: "POST",
            url: "/ticket",
            data: JSON.stringify({agents: agents, queues: queues}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(ticket) {
                pbx_connection(true);
                pbx_long_poll(ticket);
            }
        }
    )
}
