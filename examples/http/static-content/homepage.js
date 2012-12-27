String.prototype.repeat = function(cnt) {return new Array(cnt+1).join(this);}

var ringing_phone = '<img src="/img/ringing.png"/>'

function pbx_agent_status(agent, queue, status) {
    if (!status) status = 'loggedoff';
    $('#' + agent + '_' + queue + '_agent_status').attr('src', '/img/'+status+'.png');
}
function pbx_extension_status(agent, status) {
    if (!status) status = 'not_inuse';
    $('#' + agent + '_ext_status').attr('src', '/img/'+status+'.png');
}
function pbx_agent_name(agent, name) {
    $('#' + agent + '_name').html(name + ' (' + agent + ')')
}
function pbx_queue_count(queue, count) {
    $('#' + queue + '_queue_count').html(ringing_phone.repeat(count))
}
function pbx_phone_num(agent, num, name) {
    $('#' + agent + '_phone_num').html((num || '') + (name? '<br/>(' + name + ')' : ''));
}
function queue_action(action) {
    var agent = $('#agent').val();
    var queue = $('#queue').val();
    $.ajax({
        type: 'POST',
        url: '/queue/'+action+'/'+agent,
        data: JSON.stringify(
            {queue: queue,
             paused: $('#'+agent+'_'+queue+'_agent_status').attr('src').indexOf('paused') === -1}),
        contentType: 'application/json; charset=utf-8',
        dataType: 'json'
    });
}
$(function() {
    pbx_connection(false);
    $('#log-on').click(function() {
        queue_action('add');
    });
    $('#pause').click(function() {
        queue_action('pause');
    });
    $('#log-off').click(function() {
        queue_action('remove');
    });
    $('#originate').submit(function() {
        $.ajax({
            type: 'POST',
            url: '/originate/'+$('#src').val()+'/'+$('#dest').val(),
            contentType: 'application/json; charset=utf-8',
            dataType: 'json'
        });
    });
})
