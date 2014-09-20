package nachos.threads;

import java.util.LinkedList;
import java.util.Iterator;
import java.util.HashSet;
import nachos.machine.Lib;
import nachos.machine.Machine;

/**
 * A scheduler that chooses threads based on their priorities.
 * 
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the thread
 * that has been waiting longest.
 * 
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fassion to
 * all the highest-priority threads, and ignores all other threads. This has the
 * potential to starve a thread if there's always a thread waiting with higher
 * priority.
 * 
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
	/**
	 * Allocate a new priority scheduler.
	 */
	public PriorityScheduler() {
	}

	/**
	 * Allocate a new priority thread queue.
	 * 
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer priority from
	 *            waiting threads to the owning thread.
	 * @return a new priority thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new PriorityQueue(transferPriority);
	}

	public int getPriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getPriority();
	}

	public int getEffectivePriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getEffectivePriority();
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum
				&& priority <= priorityMaximum);

		getThreadState(thread).setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMaximum) {
			Machine.interrupt().restore(intStatus); // bug identified by Xiao
													// Jia @ 2011-11-04
			return false;
		}

		setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum) {
			Machine.interrupt().restore(intStatus); // bug identified by Xiao
													// Jia @ 2011-11-04
			return false;
		}

		setPriority(thread, priority - 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 0;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = 7;

	/**
	 * Return the scheduling state of the specified thread.
	 * 
	 * @param thread
	 *            the thread whose scheduling state to return.
	 * @return the scheduling state of the specified thread.
	 */
	protected ThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new ThreadState(thread);

		return (ThreadState) thread.schedulingState;
	}

	/**
	 * A <tt>ThreadQueue</tt> that sorts threads by priority.
	 */
	protected class PriorityQueue extends ThreadQueue {
		PriorityQueue(boolean transferPriority) {
			this.transferPriority = transferPriority;
		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).acquire(this);
		}

		public KThread nextThread() 
		{
			Lib.assertTrue(Machine.interrupt().disabled());
			
			ThreadState pickState = pickNextThread();
			KThread returnThread;
			if (pickState == null)
				returnThread = null;
			else 
			{
				returnThread = pickState.thread;
				pickState.acquire(this);
			}
			return returnThread;
		}

		/**
		 * Return the next thread that <tt>nextThread()</tt> would return,
		 * without modifying the state of this queue.
		 * 
		 * @return the next thread that <tt>nextThread()</tt> would return.
		 */
		protected ThreadState pickNextThread() {
			ThreadState pickState = null;
			int pickPriority = -1;
			ThreadState tmpState;
			for (Iterator<ThreadState> i = waitQueue.iterator(); i.hasNext();) 
			{
				tmpState = i.next();
				if (pickPriority < tmpState.getEffectivePriority()) 
				{
					pickState = tmpState;
					pickPriority = pickState.getEffectivePriority();
					if (pickPriority == priorityMaximum)
						break;
				}
			}
			return pickState;
		}

		public void print() 
		{
			Lib.assertTrue(Machine.interrupt().disabled());
			for (Iterator<ThreadState> i = waitQueue.iterator(); i.hasNext();) 
			{
				System.out.print(i.next().thread + " ");
			}
			
		}

		/**
		 * <tt>true</tt> if this queue should transfer priority from waiting
		 * threads to the owning thread.
		 */
		public boolean transferPriority;

		protected LinkedList<ThreadState> waitQueue = new LinkedList<PriorityScheduler.ThreadState>();

		ThreadState nowState = null;
	}

	/**
	 * The scheduling state of a thread. This should include the thread's
	 * priority, its effective priority, any objects it owns, and the queue it's
	 * waiting for, if any.
	 * 
	 * @see nachos.threads.KThread#schedulingState
	 */
	protected class ThreadState {
		/**
		 * Allocate a new <tt>ThreadState</tt> object and associate it with the
		 * specified thread.
		 * 
		 * @param thread
		 *            the thread this state belongs to.
		 */
		public ThreadState(KThread thread) {
			this.thread = thread;

			setPriority(priorityDefault);
		}

		/**
		 * Return the priority of the associated thread.
		 * 
		 * @return the priority of the associated thread.
		 */
		public int getPriority() {
			return priority;
		}

		/**
		 * Return the effective priority of the associated thread.
		 * 
		 * @return the effective priority of the associated thread.
		 */
		public int getEffectivePriority()
		{
			if (effectivePriority == -1)//revised
				effectivePriority = recalculateEffectivePriority();
			return effectivePriority;
		}

		/**
		 * Set the priority of the associated thread to the specified value.
		 * 
		 * @param priority
		 *            the new priority.
		 */
		public void setPriority(int priority) 
		{
			this.priority = priority;
			this.effectivePriority = -1;
			ThreadState tmpState = this;
			while (tmpState != null)
			{
				tmpState.recalculateEffectivePriority();
				tmpState = tmpState.fatherState;
			}
		}

		/**
		 * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
		 * the associated thread) is invoked on the specified priority queue.
		 * The associated thread is therefore waiting for access to the resource
		 * guarded by <tt>waitQueue</tt>. This method is only called if the
		 * associated thread cannot immediately obtain access.
		 * 
		 * @param waitQueue
		 *            the queue that the associated thread is now waiting on.
		 * 
		 * @see nachos.threads.ThreadQueue#waitForAccess
		 */
		public void waitForAccess(PriorityQueue waitQueue) 
		{
			waitQueue.waitQueue.add(this);
			if (!waitQueue.transferPriority)
                 return;
			fatherState = waitQueue.nowState;
			ThreadState tmpState = this;
			while (tmpState != null)
			{
				tmpState.recalculateEffectivePriority();
				tmpState = tmpState.fatherState;
			}
		}

		/**
		 * Called when the associated thread has acquired access to whatever is
		 * guarded by <tt>waitQueue</tt>. This can occur either as a result of
		 * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
		 * <tt>thread</tt> is the associated thread), or as a result of
		 * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
		 * 
		 * @see nachos.threads.ThreadQueue#acquire
		 * @see nachos.threads.ThreadQueue#nextThread
		 */
		public void acquire(PriorityQueue waitQueue) 
		{
			ThreadState tmpState = waitQueue.nowState;
			if (tmpState != null)
			{
				tmpState.waitForThisQueue.remove(waitQueue);
				while (tmpState != null)
				{
					tmpState.recalculateEffectivePriority();
					tmpState = tmpState.fatherState;
				}
			}
			
			waitQueue.nowState = this;
			waitQueue.waitQueue.remove(this);
			waitForThisQueue.add(waitQueue);
			fatherState = null;
			recalculateEffectivePriority();
			
			if (!waitQueue.transferPriority)
                return;
			
			for (Iterator<ThreadState> i = waitQueue.waitQueue.iterator(); i.hasNext();)
			{
				i.next().fatherState = this;
			}
		}

		protected int recalculateEffectivePriority()
		{
			int returnP = priority;
			for (Iterator<PriorityQueue> i = waitForThisQueue.iterator(); i.hasNext();) 
			{
                PriorityQueue pq = i.next();
                if (pq.transferPriority)
               	for (Iterator<ThreadState> j = pq.waitQueue.iterator(); j.hasNext();)
                {
               		ThreadState tmpState = j.next();
                    int k = tmpState.effectivePriority == -1 ? tmpState.recalculateEffectivePriority(): tmpState.effectivePriority;
                    if (k > returnP)
                        returnP = k;
                    if (returnP == priorityMaximum)
                    {
                    	effectivePriority = returnP;
                        return returnP;
                    }
                }
			}
			effectivePriority = returnP;
			return returnP;
        }
		
		/** The thread with which this object is associated. */
		protected KThread thread;
		/** The priority of the associated thread. */
		protected int priority;

		protected int effectivePriority = -1;

		public HashSet<PriorityQueue> waitForThisQueue = new HashSet<PriorityQueue>();
		
		protected ThreadState fatherState = null;
	}
}
