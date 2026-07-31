package com.ubtech.state;

/**
 * 机器人状态
 * @author zengdengyi
 *
 */
public enum RobotState {
	//空闲状态
	IDEL, 
	//解析JSON中
	PARSE, 
	//正在执行业务
	BUSINESS
}
