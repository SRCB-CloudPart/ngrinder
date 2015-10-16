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
        </fieldSet>
        <div>
            <span><@spring.message "agent_auto_scale.currentAdvertisedHost"/> : ${advertisedHost} / <@spring.message "agent_auto_scale.maxNodeCount"/>
                : ${totalNodeCount} / <@spring.message "agent_auto_scale.activatableNodeCount"/>
                : ${activatableNodeCount}</span>
            <button class="btn btn-info btn-mini pull-right" id="show_node_setup"
                    type="button"><@spring.message "agent_auto_scale.message.howToSetupNodes"/></button>
            <span class="pull-right" style="margin-right:20px"><i class="icon-refresh pointer-cursor" id="refresh"></i></span>
        </div>
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
                <#if autoScaleType == "aws">
                    <th class="ellipsis"><@spring.message "agent_auto_scale.list.ips"/></th>
                <#else>
                    <th class="ellipsis"><@spring.message "agent_auto_scale.list.offer"/></th>
                </#if>
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
                <td class="center">
                    <#if (autoScaleType == "aws" && node.state != "STOPPED")
                        || (autoScaleType == "mesos" && (node.state == "TASK_STAGING" || node.state == "TASK_STARTING" || node.state == "TASK_RUNNING")) >
                        <i title="<@spring.message "common.button.stop"/>" class="icon-stop node-stop pointer-cursor"
                           sid="${node.id}"></i>
                    </#if>
                </td>
            </tr>
            </@list>
            </tbody>
        </table>
        <!--content-->
    </div>
</div>


<div class="modal hide fade" id="node_setup_modal" role="dialog" style="width:700px">
    <div class="modal-header" style="border: none;">
        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">x</button>
        <h4><@spring.message "agent_auto_scale.message.howToSetupNodes"/></h4>
    </div>
    <div class="modal-body">
        <div class="form-horizontal" style="overflow-y:hidden">
            <fieldset>
            <#if autoScaleType == "aws">
                <@spring.message "agent_auto_scale.message.description"/>
            <#else>
                <@spring.message "agent_auto_scale.message.mesosReadme"/>
            </#if>
                <pre>${notesNodeSetup}</pre>
            </fieldset>
        </div>
    </div>
</div>

<script type="application/javascript">
    $(document).ready(function () {

        $("#show_node_setup").click(function () {
            $('#node_setup_modal').modal('show');
        });

        $("#refresh").click(function () {
            var ajaxObj = new AjaxObj("/agent/node_mgnt/api/refresh");
            ajaxObj.success = function () {
                location.reload();
            };
            ajaxObj.call();
        });

        $("i.node-stop").click(function () {
            var id = $(this).attr("sid");
            bootbox.confirm("<@spring.message "agent_auto_scale.message.stop.confirm"/>" + " - " + id, "<@spring.message "common.button.cancel"/>", "<@spring.message "common.button.ok"/>", function (result) {
                if (result) {
                    stopNode(id);
                }
            });
        });

        function stopNode(id) {
            var ajaxObj = new AjaxPutObj("/agent/node_mgnt/api/" + id + "?action=stop",
                    {},
                    "<@spring.message "agent_auto_scale.message.stop.success"/>");
            ajaxObj.success = function () {
                setTimeout(function () {
                            location.reload();
                        }, 2000
                );
            };
            ajaxObj.call();
        }
    });
</script>
<#include "../common/copyright.ftl">

</body>
</html>
