package com.buggyapp.slowfinalize;


public class SlowFinalizeDemo {

	//private static final Logger s_logger = LogManager.getLogger(SlowFinalizeDemo.class);
	private static volatile boolean running = true;

	public static void start() {

		running = true;
		long counter = 0;

		while (running) {

			new Object1("my-fun-data-" + counter);
			System.out.println("created: " + counter++);
		}
	}

	public static void stop() {

		running = false;
		System.out.println("Slow finalize demo stopped");
	}
}
