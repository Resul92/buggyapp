package com.buggyapp.memoryleakthread;

import java.util.HashMap;

/**
 * 
 * @author Ram Lakshmanan
 */
public class MapManager {

	//private static final Logger s_logger = LogManager.getLogger(MapManager.class);
	private static volatile boolean flag = true;

	HashMap<Object, Object> myMap = new HashMap<>();

	public static void setFlag(boolean newValue) {
		flag = newValue;
	}

	public void grow() {

		long counter = 0;
		while (flag) {
		
			if (counter % 1000 == 0) {
				
				System.out.println("Inserted 1000 Records to map!");
				//s_logger.info("Inserted 1000 Records to map!");
			}
			
			try {
				//Thread.sleep(1);
			} catch (Exception e) {
				
			}
			myMap.put("key" + counter, "Large stringgggggggggggggggggggggggggggg"
					+ "ggggggggggggggggggggggggggggggggggggggggggggggggggggg" + counter);			
			++counter;
		}
	}
}
