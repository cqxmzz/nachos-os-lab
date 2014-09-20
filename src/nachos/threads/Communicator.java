package nachos.threads;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	public Communicator() 
	{
		commLock = new Lock();
		listener = new Condition(commLock);
		speaker = new Condition(commLock);
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word
	 *            the integer to transfer.
	 */
	public void speak(int word) 
	{
		commLock.acquire();
        while (count == 1)
        	speaker.sleep();
        speakWord = word;
        count++;
        if (count == 1)
            listener.wake();
        commLock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		commLock.acquire();
		while (count == 0)
            listener.sleep();
        int res = speakWord;
        count--;
        if (count == 0)
            speaker.wake();
        commLock.release();
        return res;
	}
	
	Lock commLock;
	private Condition listener;
	private Condition speaker;

	private int speakWord;
	private int count = 0;
}
