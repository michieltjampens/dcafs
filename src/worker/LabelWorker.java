package worker;

import das.CommandPool;
import org.tinylog.Logger;
import java.util.concurrent.*;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed.
 */
public class LabelWorker implements Runnable {
	private BlockingQueue<Datagram> dQueue;      // The queue holding raw data for processing
	private boolean goOn=true;
	protected CommandPool reqData;

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>());

	String lastOrigin="";
	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 *
	 * @param dQueue The queue to use
	 */
	public LabelWorker(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
	}

	/**
	 * Set the BaseReq for this worker to use
	 *
	 * @param commandPool The default BaseReq or extended one
	 */
	public void setCommandReq(CommandPool commandPool) {
		this.reqData = commandPool;
	}

	/* ******************************** Q U E U E S **********************************************/
	public int getWaitingQueueSize(){
		return executor.getQueue().size();
	}
	/**
	 * Get the queue for adding work for this worker
	 *
	 * @return The qeueu
	 */
	public BlockingQueue<Datagram> getQueue() {
		return dQueue;
	}

	/**
	 * Set the queue for adding work for this worker
	 *
	 * @param d The queue
	 */
	public void setQueue(BlockingQueue<Datagram> d) {
		this.dQueue = d;
	}
	public void stop(){
		goOn=false;
	}
	/* ************************************** RUNNABLES ******************************************************/
	@Override
	public void run() {

		if (this.reqData == null) {
			Logger.error("Not starting without proper BaseReq");
			return;
		}
		while (goOn) {
			try {
				Datagram d = dQueue.take();
				lastOrigin=d.getOriginID();
				String label = d.getLabel();

				if (label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}

				if( d.label.contains(":") ){
					switch (d.label.split(":")[0]) {
						case "log" -> {
							switch (d.label.split(":")[1]) {
								case "info" -> Logger.info(d.getData());
								case "warn" -> Logger.warn(d.getData());
								case "error" -> Logger.error(d.getData());
							}
						}
						case "cmd" -> executor.execute(() -> {
								String response = reqData.executeCommand(d, false);
								if( d.getOriginID().startsWith("telnet")&&d.getWritable()!=null){
									d.getWritable().writeLine(response);
									String[] split = d.getLabel().split(":");
									d.getWritable().writeString((split.length >= 2 ? "<" + split[1] : "") + ">");
								}
							});
						default -> Logger.error("Unknown label: " + label);
					}
				}else {
					switch (label) {
						case "system", "cmd", "matrix" -> executor.execute(() -> reqData.executeCommand(d, false));
						case "email" -> executor.execute(() -> reqData.emailResponse(d));
						default -> Logger.error("Unknown label: " + label);
					}
				}
			} catch( RejectedExecutionException e){
				Logger.error(e.getMessage());
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}
}
