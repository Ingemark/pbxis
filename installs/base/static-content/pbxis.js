function pbx_connection(is_connected) {}
function pbx_agent_status(queue, status) {}
function pbx_extension_status(status) {}
function pbx_queue_count(queue, count) {}
function pbx_phone_num(num) {}


function pbx_long_poll(key) {
    $.getJSON("/agent/"+key, function(r) {
        if (handle_events(r.events)) pbx_long_poll(r.key);
        else pbx_connection(false);
    }).error(function() {pbx_connection(false)});
}

function handle_events(events) {
    var result = true;
    $.each(events, function(_, e) {
        //console.log("Event " + e);
        switch (e[0]) {
        case "queueMemberStatus":
            $.each(e[1], pbx_agent_status);
            break;
        case "extensionStatus":
            pbx_extension_status(e[1]);
            break;
        case "queueCount":
            $.each(e[1], pbx_queue_count);
            break;
        case "phoneNum":
            pbx_phone_num(e[1]);
            break;
        case "requestInvalidated": result = false;
        }
    });
    return result;
}

function pbx_start(agent, queues) {
    $.ajax(
        {
            type: "POST",
            url: "/agent/"+agent,
            data: JSON.stringify({queues: queues}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(r) {
                pbx_connection(true);
                pbx_long_poll(r);
            }
        }
    )
}
