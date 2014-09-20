package nachos.vm;

import java.util.Hashtable;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.Vector;

import nachos.machine.*;
import nachos.threads.Lock;
import nachos.userprog.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel
{
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel()
	{
		super();
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args)
	{
		super.initialize(args);
		extractArguments(args);
		String tmpString = getStringArgument("swapFile");
		if (tmpString != null) swapFileName = tmpString;
		swapFile = Machine.stubFileSystem().open(swapFileName, true);
		pageLock = new Lock();
		for (int i = 0; i < clocklen; i++)
			clockadd(i);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest()
	{
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run()
	{
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate()
	{
		swapFile.close();
		Machine.stubFileSystem().remove(swapFile.getName());
		super.terminate();
	}

	// TLBScheduler
	public static void clearTLB(int pid)
	{
		TranslationEntry nte = new TranslationEntry();
		nte.valid = false;
		for (int i = 0; i < tlbSize; i++)
		{
			TranslationEntry te = Machine.processor().readTLBEntry(i);
			if (te.dirty) writePageEntry(pid, te);
			Machine.processor().writeTLBEntry(i, nte);
		}
	}

	public static void clearTLB(int pid, int vpn)
	{
		TranslationEntry nte = new TranslationEntry();
		nte.valid = false;
		for (int i = 0; i < tlbSize; i++)
		{
			TranslationEntry te = Machine.processor().readTLBEntry(i);
			if (te.vpn == vpn)
			{
				if (te.dirty) writePageEntry(pid, te);
				Machine.processor().writeTLBEntry(i, nte);
			}
		}
	}

	public static boolean handleTLBMiss(int pid, int vpn)
	{
		TranslationEntry te = getPageEntry(pid, vpn);
		if (te == null) return false;
		Machine.processor().writeTLBEntry(VMKernel.tlbplace++%4, te);
		return true;
	}

	/**
	 * Write page table entry
	 * 
	 * @param pid
	 * @param te
	 */
	public static void writePageEntry(int pid, TranslationEntry te)
	{
		coreMap[te.ppn] = newItem(pid, te.vpn);
		coreMapEntry[te.ppn] = te;
		invertedTable.put(newItem(pid, te.vpn), te.ppn);
	}

	/**
	 * Use some strategy to remove from ppn and wait for swap in
	 * 
	 * @return
	 */
	static int findPPNEntryToRemove()
	{
		int ppn = clockremoveFirst();
		TranslationEntry te = getEntry(ppn);
		while (te != null && te.used)
		{
			te.used = false;
			clockadd(ppn);
			ppn = clockremoveFirst();
			te = getEntry(ppn);
		}
		return ppn;
	}

	/**
	 * Get the page that pid&vpn fixed.
	 * 
	 * If the page in pageTable, then give it and return. Otherwise to
	 * handlePageFault().
	 * 
	 * @param pid
	 * @param vpn
	 * @param loader
	 *            donothing but handle page fault
	 * @return
	 */
	public static TranslationEntry getPageEntry(int pid, int vpn)
	{
		TranslationEntry te = getEntry(invertedTable.get(newItem(pid, vpn)));
		if (te == null)
		{
			handlePageFault(pid, vpn);
			te = getEntry(invertedTable.get(newItem(pid, vpn)));
		}
		return te;
	}

	/**
	 * clear the memory to be stack
	 * 
	 * @param ppn
	 */
	public static void flushMemory(int ppn)
	{
		byte[] data = Machine.processor().getMemory();
		int start = Processor.makeAddress(ppn, 0);
		int end = start + Processor.pageSize;
		for (int i = start; i < end; i++)
			data[i] = 0;
	}

	/**
	 * Page not in pageTable.Stratage:
	 * 
	 * 1.Find a page to swap to file:fppn. Clear the pageTabel & tlb to correct.
	 * 
	 * 2.Check if the fixed page in swapFile.
	 * 
	 * 2.1 If true, swapIn and return.
	 * 
	 * 2.2 Otherwise load page by loader or flushmemory.
	 * 
	 * @param pid
	 * @param vpn
	 * @param loader
	 */
	public static void handlePageFault(int pid, int vpn)
	{
		pageLock.acquire();
		int fppn = findPPNEntryToRemove();
		int fpid = getPID(fppn);
		int fvpn = getVPN(fppn);
		if (fpid == pid) clearTLB(fpid, fvpn);
		TranslationEntry te = coreMapEntry[fppn];
		swapToFile(fpid, fvpn, te);
		removePage(fpid, fvpn);
		int ppn = fppn;
		te = swapToMemory(pid, vpn, ppn);
		boolean loadCoff = (te == null);
		if (loadCoff)
			te = new TranslationEntry(vpn, ppn, true, false, false, false);
		coreMap[te.ppn] = newItem(pid, te.vpn);
		coreMapEntry[te.ppn] = te;
		invertedTable.put(newItem(pid, te.vpn), te.ppn);
		clockadd(ppn);
		if (loadCoff)
		{
			if (vpn >= 0 && vpn < ((VMProcess) UserProcess.allProcess.get(pid)).codePages)
			{
				TranslationEntry tte = ((VMProcess) UserProcess.allProcess
						.get(pid)).loadSection(vpn, ppn);
				te.readOnly = tte.readOnly;
			}
			else
			{
				flushMemory(ppn);
			}
		}
		pageLock.release();
	}

	// for clock
	static int clockremoveFirst()
	{
		int point = clockhead;
		clockhead = (clockhead + 1) % clocklen;
		return clockppn[point];
	}

	static void clockadd(int n)
	{
		int point = clocktail;
		clocktail = (clocktail + 1) % clocklen;
		clockppn[point] = n;
	}

	// for hash
	static public int newItem(int pid, int vpn)
	{
		return pid * maxVPNSize + vpn;
	}

	static public int decodePID(int key)
	{
		return key / maxVPNSize;
	}

	public static int getPID(int ppn)
	{
		return coreMap[ppn] / maxVPNSize;
	}

	public static int getVPN(int ppn)
	{
		return coreMap[ppn] % maxVPNSize;
	}

	/**
	 * Get entry by ppn
	 * 
	 * @param ppn
	 * @return
	 */
	public static TranslationEntry getEntry(Integer ppn)
	{
		if (ppn == null) return null;
		return coreMapEntry[ppn];
	}

	/**
	 * Remove pair pid&vpn
	 * 
	 * @param pid
	 * @param vpn
	 */
	public static void removePage(int pid, int vpn)
	{
		int hash = newItem(pid, vpn);
		if (invertedTable.containsKey(hash))
		{
			int ppn = invertedTable.remove(hash);
			coreMap[ppn] = -1;
			coreMapEntry[ppn] = null;
		}
	}

	/**
	 * Swap to file from memory.
	 * 
	 * @param pid
	 * @param vpn
	 * @param te
	 * @return
	 */
	public static int swapToFile(int pid, int vpn, TranslationEntry te)
	{
		if (te == null) return 0;
		int hash = newItem(pid, vpn);
		
		//TODO
		//if (!te.dirty && usedSlot.containsKey(hash))
		//{
		//	swapedPage.put(hash, te);
		//	return 0;
		//}
		Integer newSlot = usedSlot.get(hash);
		if (newSlot == null) newSlot = getSlot();
		swapedPage.put(hash, te);
		usedSlot.put(hash, newSlot);
		return swapFile.write(newSlot * pageSize, Machine.processor() .getMemory(), Processor.makeAddress(te.ppn, 0), pageSize);
	}

	/**
	 * Swap to memory from file.
	 * 
	 * @param pid
	 * @param vpn
	 * @param ppn
	 * @return
	 */
	public static TranslationEntry swapToMemory(int pid, int vpn, int ppn)
	{
		int hash = newItem(pid, vpn);
		if (!swapedPage.containsKey(hash)) return null;
		int slot = usedSlot.get(hash);
		int readLen = read(slot * pageSize, Machine.processor().getMemory(),
				Processor.makeAddress(ppn, 0));
		if (readLen < pageSize) return null;
		TranslationEntry te = swapedPage.get(hash);
		te.ppn = ppn;
		te.dirty = false;
		te.used = false;
		return te;
	}

	public static int read(int pos, byte[] buf, int offset)
	{
		int readLen = swapFile.read(pos, buf, offset, pageSize);
		if (readLen < pageSize) return -1;
		return readLen;
	}

	private static int getSlot()
	{
		if (freeSlots.isEmpty()) return maxPage++;
		return freeSlots.removeFirst();
	}

	// copied from AutoGrader.java
	private void extractArguments(String[] args)
	{
		String testArgsString = Config.getString("AutoGrader.testArgs");
		if (testArgsString == null)
		{
			testArgsString = "";
		}
		for (int i = 0; i < args.length;)
		{
			String arg = args[i++];
			if (arg.length() > 0 && arg.charAt(0) == '-')
			{
				if (arg.equals("-#"))
				{
					Lib.assertTrue(i < args.length,
							"-# switch missing argument");
					testArgsString = args[i++];
				}
			}
		}
		StringTokenizer st = new StringTokenizer(testArgsString, ",\n\t\f\r");
		while (st.hasMoreTokens())
		{
			StringTokenizer pair = new StringTokenizer(st.nextToken(), "=");
			Lib.assertTrue(pair.hasMoreTokens(), "test argument missing key");
			String key = pair.nextToken();
			Lib.assertTrue(pair.hasMoreTokens(), "test argument missing value");
			String value = pair.nextToken();
			testArgs.put(key, value);
		}
	}

	String getStringArgument(String key)
	{
		String value = (String) testArgs.get(key);
		return value;
	}

	// arguments for swapfile name
	private Hashtable<String, String> testArgs = new Hashtable<String, String>();

	private static final char dbgVM = 'v';

	// PageTable
	public static int[] coreMap = new int[Machine.processor().getNumPhysPages()];

	public static TranslationEntry[] coreMapEntry = new TranslationEntry[Machine
			.processor().getNumPhysPages()];

	public static Hashtable<Integer, Integer> invertedTable = new Hashtable<Integer, Integer>();

	final static int maxVPNSize = 1000000;

	// Swaper
	static LinkedList<Integer> freeSlots = new LinkedList<Integer>();

	static Hashtable<Integer, TranslationEntry> swapedPage = new Hashtable<Integer, TranslationEntry>();

	static Hashtable<Integer, Integer> usedSlot = new Hashtable<Integer, Integer>();

	static String swapFileName = "SWAP";

	static OpenFile swapFile;

	static int maxPage = 0;

	static Vector<byte[]> dbgMemory = new Vector<byte[]>();

	static int pageSize = Processor.pageSize;

	public static Lock pageLock;

	// clock
	static int clocklen = coreMap.length;

	static int clockhead = 0;

	static int clocktail = 0;

	static int[] clockppn = new int[clocklen];

	// TLBScheduler
	public static int tlbSize = Machine.processor().getTLBSize();
	
	public static int tlbplace = 0;
}
