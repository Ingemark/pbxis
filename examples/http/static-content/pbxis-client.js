function pbx_connection(is_connected) {}
function pbx_agent_status(queue, status) {}
function pbx_extension_status(status) {}
function pbx_queue_count(queue, count) {}
function pbx_phone_num(num) {}

function handle_event(e) {
    console.log("Handling event " + e.type + ": " + e.data);
    switch (e.type) {
    case "queueMemberStatus":
        $.each(e.data[0], pbx_agent_status);
        break;
    case "extensionStatus":
        pbx_extension_status(e.data[0]);
        break;
    case "queueCount":
        $.each(e.data[0], pbx_queue_count);
        break;
    case "phoneNumber":
        pbx_phone_num(e.data[0]);
        break;
    case "closed":
        pbx_connection(false);
        return false;
    }
    return true;
}
