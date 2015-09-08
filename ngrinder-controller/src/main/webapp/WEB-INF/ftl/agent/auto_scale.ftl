<!DOCTYPE html>
<html>
<head>
<#include "../common/common.ftl"/>
<#include "../common/datatables.ftl"/>
    <title><@spring.message "agent_auto_scale.title"/></title>
</head>
<body>
<div id="wrap">
<#include "../common/navigator.ftl">
    <div class="container">
        <fieldSet>
            <legend class="header">
                <@spring.message "agent_auto_scale.list.title"/>
            </legend>
            <span class="pull-right"><@spring.message "agent_auto_scale.maxNodeCount"/> : ${totalNodeCount} / <@spring.message "agent_auto_scale.activatableNodeCount"/> : ${activatableNodeCount}</span>
        </fieldSet>

        <table class="table table-striped table-bordered ellipsis" id="agent_table">
            <colgroup>
                <col width="200">
                <col width="200">
                <col width="200">
                <col width="*">
                <col width="100">
            </colgroup>
            <thead>
            <tr>
                <th><@spring.message "agent_auto_scale.list.name"/></th>
                <th><@spring.message "agent_auto_scale.list.id"/></th>
                <th><@spring.message "agent_auto_scale.list.state"/></th>
                <th class="ellipsis"><@spring.message "agent_auto_scale.list.ips"/></th>
                <th class="ellipsis"><@spring.message "common.label.actions"/></th>
            </tr>
            </thead>
            <tbody>
			<@list list_items=nodes others="table_list" colspan="4"; node>
            <tr>
                <td>${node.name}</td>
                <td>${node.id}</td>
                <td>${node.state}</td>
                <td><#list node.ips as each>${each}<br/></#list></td>
                <td>
                    <i title="<@spring.message "common.button.stop"/>"
                       class="icon-stop node-stop pointer-cursor" sid="${node.id}" ></i>
                </td>
            </tr>
			</@list>
            </tbody>
        </table>
        <!--content-->
    </div>
</div>
<script type="application/javascript">
    $(document).ready(function() {
        $("i.node-stop").click(function () {
            var id = $(this).attr("sid");
            bootbox.confirm("<@spring.message "agent_auto_scale.message.stop.confirm"/>" + " - "  + id, "<@spring.message "common.button.cancel"/>", "<@spring.message "common.button.ok"/>", function (result) {
                if (result) {
                    stopNode(id);
                }
            });
        });

        function stopNode(id) {
            var ajaxObj = new AjaxPutObj("/agent/node_mgnt/api/" + id + "?action=stop",
                    "<@spring.message "agent_auto_scale.message.stop.success"/>",
                    "<@spring.message "agent_auto_scale.message.stop.error"/>");
            ajaxObj.type = "DELETE";
            ajaxObj.success = function () {
                setTimeout(function () {
                    setTimeout(location.reload, 1000);
                }, 500);
            };
            ajaxObj.call();
        }
    });
</script>
<#include "../common/copyright.ftl">

</body>
</html>
