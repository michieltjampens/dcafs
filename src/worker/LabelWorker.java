package worker;

import das.CommandPool;
import io.telnet.TelnetCodes;
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
			Logger.error("Not starting without proper CommandPool");
			return;
		}
		while (goOn) {
			try {
				Datagram d = dQueue.take();
				String label = d.getLabel();

				if (label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}
				var labelSplit = d.getLabel().split(":", 2);
				if (labelSplit.length == 2) {
					switch (labelSplit[0]) {
						case "log" -> handleLogCmd(labelSplit[1], d.getData());
						case "cmd" -> executor.execute(() -> handleCommand(d));
						default -> Logger.error("Unknown label: " + labelSplit[0]);
					}
				} else {
					switch (label) {
						case "system", "cmd", "matrix" -> {
							if (d.isSilent()) {
								executor.execute(() -> reqData.quickCommand(d));
							} else {
								executor.execute(() -> reqData.executeCommand(d, false));
							}
						}
						case "email" -> executor.execute(() -> reqData.emailResponse(d));
						default -> Logger.error("Unknown label: " + label);
					}
				}
			} catch (RejectedExecutionException e) {
				Logger.error(e.getMessage());
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	private void handleCommand(Datagram d) {
		String response = reqData.executeCommand(d, false);
		if (d.getOriginID().startsWith("telnet") && d.getWritable() != null) {
			d.getWritable().writeLine(response);
			d.getWritable().writeString(
					TelnetCodes.TEXT_YELLOW + // print the prefix in yellow
							d.getLabel().substring(d.getLabel().indexOf(":") + 1) + ">"
							+ TelnetCodes.TEXT_DEFAULT); // return to default color
		}
	}

	private void handleLogCmd(String logLevel, String data) {
		switch (logLevel) {
			case "info" -> Logger.info(data);
			case "warn" -> Logger.warn(data);
			case "error" -> Logger.error(data);
		}
	}
}
