var q_status = {}
var ext_status = "unknown"

function log_queue_state() {
    $.each(q_status, function(k,v) {console.log("New queue state: " + k + ":" + v)});
}

function pbx_long_poll(key) {
    $.getJSON("/agent/"+key, function(r) {
        if (handle_events(r.events)) pbx_long_poll(r.key);
    });
}

function handle_events(events) {
    var result = true;
    $.each(events, function(_, e) {
        console.log("Event " + e);
        switch (e[0]) {
        case "queueMemberStatus":
            $.each(e[1], function(k,v) {q_status[k] = v});
            log_queue_state();
            break;
        case "extensionStatus":
            ext_status = e[1];
            console.log("New extension status: " + ext_status);
            break;
        case "requestInvalidated": result = false;
        }
    });
    return result;
}

function pbx_start() {
    $.ajax(
        {
            type: "POST",
            url: "/agent/148",
            data: JSON.stringify({queues: ["700", "3000"]}),
            contentType: "application/json; charset=utf-8",
            dataType: "json",
            success: function(r) {
                q_status = r.queues;
                log_queue_state();
                pbx_long_poll(r.key);
            }
        }
    )
}
