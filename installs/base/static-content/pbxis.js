var q_state = {}
var ext_state = "unknown"

function log_queue_state() {
    $.each(q_state, function(k,v) {console.log("New queue state: " + k + ":" + v)});
}

function pbx_long_poll() {
    $.getJSON(polling_url, function(r)
              {
                  $.each(r.events, function(_, e) {
                      console.log("Event " + e);
                      switch (e[0]) {
                      case "queueMemberStatus":
                          $.each(e[1], function(k,v) {q_state[k] = v});
                          log_queue_state();
                          break;
                      case "extensionStatus":
                          ext_state = e[1];
                          console.log("New extension status: " + ext_state);
                          break;
                      }
                  });
                  pbx_long_poll(r.key);
              }
             )
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
                q_state = r.queues;
                log_queue_state();
                pbx_long_poll(r.key);
            }
        }
    )
}
