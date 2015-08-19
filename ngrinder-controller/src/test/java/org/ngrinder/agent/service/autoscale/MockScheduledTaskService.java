package org.ngrinder.agent.service.autoscale;

import org.ngrinder.infra.schedule.ScheduledTaskService;

/**
 * Created by junoyoon on 15. 8. 18.
 */
public class MockScheduledTaskService extends ScheduledTaskService {
	@Override
	public void runAsync(Runnable runnable) {
		runnable.run();
	}
}
