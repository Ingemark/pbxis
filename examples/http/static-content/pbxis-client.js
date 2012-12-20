function pbx_connection(is_connected) {}
function pbx_agent_status(queue, status) {}
function pbx_extension_status(status) {}
function pbx_queue_count(queue, count) {}
function pbx_phone_num(num) {}

function handle_event(e) {
    console.log("Handling event " + JSON.stringify(e));
    switch (e.type) {
    case "queueMemberStatus":
        pbx_agent_status(e.queue, e.status);
        break;
    case "extensionStatus":
        pbx_extension_status(e.status);
        break;
    case "queueCount":
        pbx_queue_count(e.queue, e.count);
        break;
    case "phoneNumber":
        pbx_phone_num(e.number);
        break;
    case "closed":
        pbx_connection(false);
        return false;
    }
    return true;
}
