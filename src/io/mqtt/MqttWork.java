package io.mqtt;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.tinylog.Logger;

public class MqttWork {
	String topic;
	int qos=0;
	int attempt = 0;
	boolean valid=true;
	byte[] data;

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
		if (val.getClass().equals(Double.class)) {
			data = Double.toString((double)val).getBytes();
		} else if (val.getClass().equals(Integer.class)) {
			data = Integer.toString((int)val).getBytes();
		} else if (val.getClass().equals(Boolean.class)) {
			data = Boolean.toString((boolean)val).getBytes();
		} else if (val.getClass().equals(String.class)) {
			data = ((String)val).getBytes();
		}else{
			Logger.error("mqtt -> Invalid class given, topic:"+topic);
			valid=false;
		}
	}
	public String toString(){
		return "Topic: "+topic+" -> data:"+new String(data) +" -> qos: "+qos;
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
		return new MqttMessage(data);
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
