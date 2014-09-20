package nachos.threads;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

/**
 * A scheduler that chooses threads using a lottery.
 * 
 * <p>
 * A lottery scheduler associates a number of tickets with each thread. When a
 * thread needs to be dequeued, a random lottery is held, among all the tickets
 * of all the threads waiting to be dequeued. The thread that holds the winning
 * ticket is chosen.
 * 
 * <p>
 * Note that a lottery scheduler must be able to handle a lot of tickets
 * (sometimes billions), so it is not acceptable to maintain state for every
 * ticket.
 * 
 * <p>
 * A lottery scheduler must partially solve the priority inversion problem; in
 * particular, tickets must be transferred through locks, and through joins.
 * Unlike a priority scheduler, these tickets add (as opposed to just taking the
 * maximum).
 */
public class LotteryScheduler extends PriorityScheduler
{
	/**
	 * Allocate a new lottery scheduler.
	 */
	public LotteryScheduler() 
	{
		super();
	}

	/**
	 * Allocate a new lottery thread queue.
	 * 
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer tickets from
	 *            waiting threads to the owning thread.
	 * @return a new lottery thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) 
	{
		return new LotteryQueue(transferPriority);
	}
	
	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 1;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = Integer.MAX_VALUE;

	
	protected class LotteryQueue extends PriorityQueue {
		public LotteryQueue(boolean transferPriority) 
		{
			super(transferPriority);
		}

		/**
		 * Return the next thread that <tt>nextThread()</tt> would return,
		 * without modifying the state of this queue.
		 * 
		 * @return the next thread that <tt>nextThread()</tt> would return.
		 */
		protected nachos.threads.PriorityScheduler.ThreadState pickNextThread() 
		{
			Random rdm = new Random();
			nachos.threads.PriorityScheduler.ThreadState pickState = null;
			int allPriority = 0;
			nachos.threads.PriorityScheduler.ThreadState tmpState;
			for (Iterator<nachos.threads.PriorityScheduler.ThreadState> i = waitQueue.iterator(); i.hasNext();) 
			{
				tmpState = i.next();
				allPriority += tmpState.getEffectivePriority();
			}
			if (allPriority == 0) return null;
			int pickTicket = rdm.nextInt(allPriority);
			int ticketCount = 0;
			for (Iterator<nachos.threads.PriorityScheduler.ThreadState> i = waitQueue.iterator(); i.hasNext();) 
			{
				tmpState = i.next();
				ticketCount += tmpState.getEffectivePriority();
				if (ticketCount >= pickTicket)
				{
					pickState = tmpState;
					break;
				}
			}
			return pickState;
		}

		//protected LinkedList<ThreadState> waitQueue = new LinkedList<LotteryScheduler.ThreadState>();

		ThreadState nowState = null;
	}
	protected class ThreadState extends PriorityScheduler.ThreadState
	{
		public ThreadState(KThread thread) 
		{
			super(thread);
		}
		
		protected int recalculateEffectivePriority()
		{
			int returnP = priority;
			for (Iterator<LotteryQueue> i = waitForThisQueue.iterator(); i.hasNext();) 
			{
                LotteryQueue pq = i.next();
                if (pq.transferPriority)
               	for (Iterator<nachos.threads.PriorityScheduler.ThreadState> j = pq.waitQueue.iterator(); j.hasNext();)
                {
               		nachos.threads.PriorityScheduler.ThreadState tmpState = j.next();
                    int k = tmpState.effectivePriority == -1 ? tmpState.recalculateEffectivePriority(): tmpState.effectivePriority;
                    returnP += k;
                }
			}
			effectivePriority = returnP;
			return returnP;
        }

		public HashSet<LotteryQueue> waitForThisQueue = new HashSet<LotteryQueue>();
	}
}

