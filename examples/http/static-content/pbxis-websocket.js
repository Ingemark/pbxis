function pbx_connection(is_connected) {}
function pbx_agent_status(queue, status) {}
function pbx_extension_status(status) {}
function pbx_queue_count(queue, count) {}
function pbx_phone_num(num) {}


function handle_event(e) {
    e = JSON.parse(e.data);
    console.log("Websocket event " + e);
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
    case "phoneNumber":
        pbx_phone_num(e[1]);
        break;
    case "closed": return false;
    }
    return true;
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
                var socket = new WebSocket("ws://localhost:58615/agent/"+ticket+"/websocket");
                socket.onopen = function() { pbx_connection(true); }
                socket.onclose = function() { pbx_connection(false); }
                socket.onmessage = handle_event;
            }
        }
    )
}
