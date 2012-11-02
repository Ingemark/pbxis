function handle_events(events) {
    var result = true;
    $.each(events, function(_, e) {
        result &= handle_event({"type":e[0], "data":e.slice(1)});
    });
    return result;
}

function pbx_long_poll(ticket) {
    $.getJSON("/agent/"+ticket+"/long-poll", function(r) {
        if (handle_events(r)) pbx_long_poll(ticket);
        else pbx_connection(false);
    }).error(function() {pbx_connection(false)});
}

function pbx_start(agent, queues) {
    $.ajax(
        {
            type: "POST",
            url: "/agent/"+agent,
            data: JSON.stringify({queues: queues}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(ticket) {
                pbx_connection(true);
                pbx_long_poll(ticket);
            }
        }
    )
}
