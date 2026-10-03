package cn.managame.demo.common.data;

import cn.managame.spring.data.EnableGameData;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableGameData(basePackages = "cn.managame.demo")
public class GameDataConfig {}
