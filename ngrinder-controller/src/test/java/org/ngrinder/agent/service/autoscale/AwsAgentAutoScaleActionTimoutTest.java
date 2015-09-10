package org.ngrinder.agent.service.autoscale;

import org.junit.Test;
import org.ngrinder.common.util.ThreadUtils;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.ngrinder.common.util.ThreadUtils.sleep;


public class AwsAgentAutoScaleActionTimoutTest extends BaseAwsAgentAutoScaleActionTest {


	protected AwsAgentAutoScaleAction createAction() {
		return spy(new AwsAgentAutoScaleAction() {
			@Override
			protected int getTouchCacheDuration() {
				return 3;
			}
		});
	}

	@Test
	public void test1() {
		awsAgentAutoScaleAction.touch("wow");
		sleep(4000);
		awsAgentAutoScaleAction.touch("wow");
		verify(awsAgentAutoScaleAction).stopNode("wow");
	}
}
