package io.mqtt;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.tinylog.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class MqttWork {
	String topic;
	int qos=0;
	int attempt = 0;
	TYPE type=TYPE.DOUBLE;
	Object value;
	boolean valid=true;
	enum TYPE{INT,DOUBLE,BOOLEAN,TEXT};
	/**
	 * Constructor that also adds a value 
	 * @param group The group this data is coming from
	 * @param parameter The parameter to update
	 * @param value The new value
	 */
	public MqttWork( String group, String parameter, Object value) {
		topic=group+"/"+parameter;
		setValue(value);


	}
	public MqttWork( String topic, Object value) {
		int index = topic.indexOf("/");
		if( index == -1 ){
			Logger.error( "No topic given in mqttwork: "+topic);
			valid=false;
		}else{
			this.topic=topic;
			setValue(value);
		}
		
	}
	private void setValue( Object val){
		value=val;
		if (value.getClass().equals(Double.class)) {
			type=TYPE.DOUBLE;
		} else if (value.getClass().equals(Integer.class)) {
			type=TYPE.INT;
		} else if (value.getClass().equals(Boolean.class)) {
			type=TYPE.BOOLEAN;
		} else if (value.getClass().equals(String.class)) {
			type=TYPE.TEXT;
		}else{
			Logger.error("mqtt -> Invalid class given, topic:"+topic);
			valid=false;
		}
	}
	/*  SETTINGS */
	/**
	 * Change te QoS for this message
	 * @param qos The new QoS value to use
	 */
	public void alterQos( int qos ) {
		this.qos=qos;
	}
	/**
	 * Get the device name given 
	 * @return The name of the device this data relates to
	 */
	public String getTopic() {
		return topic;
	}
	public boolean isInvalid(){
		return !valid;
	}
	public MqttMessage getMessage(){
		return switch(type){
			case INT -> new MqttMessage(Integer.toString((int)value).getBytes());
			case DOUBLE -> new MqttMessage(Double.toString((double)value).getBytes());
			case BOOLEAN -> new MqttMessage(Boolean.toString((boolean)value).getBytes());
			case TEXT -> new MqttMessage(((String)value).getBytes());
		};
	}
	/* ********************************* ADDING DATA ******************************************************** */
	public MqttWork qos(int qos){
		this.qos=qos;
		return this;
	}
	public void incrementAttempt() {
		attempt++;
	}
}
