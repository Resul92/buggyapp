package com.buggyapp.exceptions;



public class ExceptionsDemo {

	public static long exceptionCounter = 0;
	private static volatile boolean running = true;

	public void start() {

		running = true;

		for (int i = 0; i < Integer.MAX_VALUE && running; ++i) {

			try {

				// Since dividing by zero it will result in Exception.
				int result = i / 0;
			} catch (Exception e) {
				++exceptionCounter;
			}
		}

		System.out.println("Total Exceptions: " + exceptionCounter);
	}

	public static void stop() {

		running = false;
		System.out.println("Exceptions demo stopped");
	}
}
