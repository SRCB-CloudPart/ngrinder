package org.ngrinder.agent.service.autoscale;

import org.ngrinder.infra.schedule.ScheduledTaskService;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by junoyoon on 15. 8. 18.
 */
public class ThreadedScheduledTaskService extends ScheduledTaskService {
	ExecutorService executors = Executors.newFixedThreadPool(3);
	Timer timer = new Timer();

	@Override
	public void runAsync(Runnable runnable) {
		executors.submit(runnable);
	}

	public void addFixedDelayedScheduledTask(final Runnable runnable, int delay) {
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				runnable.run();
			}
		}, delay, delay);
	}

	public void destroy() {
		timer.cancel();
		executors.shutdownNow();
	}

}
