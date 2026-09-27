package com.shivang.obd;

import com.shivang.obd.audio.AudioStorageProperties;
import com.shivang.obd.telephony.FreeSwitchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FreeSwitchProperties.class, AudioStorageProperties.class})
public class ObdApplication {

	public static void main(String[] args) {
		SpringApplication.run(ObdApplication.class, args);
	}

}
