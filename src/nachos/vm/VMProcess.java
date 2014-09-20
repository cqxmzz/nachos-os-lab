package nachos.vm;

import java.util.Enumeration;

import nachos.machine.*;
import nachos.userprog.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess
{
	/**
	 * Allocate a new process.
	 */
	public VMProcess()
	{
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState()
	{
		VMKernel.clearTLB(processID);
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 * 
	 * Do nothing to let tlb miss
	 */
	public void restoreState()
	{
	}

	public int getPPN(int vpn)
	{
		TranslationEntry te = null;
		if (te == null) te = VMKernel.getPageEntry(processID, vpn);
		te.used = true;
		return te.ppn;
	}
	
	static int k = 0;
	
	public int writePPN(int vpn)
	{
		TranslationEntry te = null;
		if (te == null) te = VMKernel.getPageEntry(processID, vpn);
		if (te.readOnly) return -1;
		te.used = true;
		te.dirty = true;
		return te.ppn;
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	public boolean loadSections()
	{
		int numSection = coff.getNumSections();
		for (int i = 0; i < numSection; i++)
			codePages += coff.getSection(i).getLength();
		pageSectionNum = new int[codePages];
		pageSectionOffset = new int[codePages];
		for (int i = 0; i < numSection; i++)
		{
			CoffSection cs = coff.getSection(i);
			int len = cs.getLength();
			int vpn = cs.getFirstVPN();
			for (int j = 0; j < len; j++)
			{
				pageSectionNum[vpn] = i;
				pageSectionOffset[vpn++] = j;
			}
		}
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 * 
	 */
	protected void unloadSections()
	{
		VMKernel.clearTLB(processID);
		for (int i = 0; i < VMKernel.coreMap.length; i++)
		{
			if (VMKernel.getPID(i) == processID)
				VMKernel.removePage(processID, VMKernel.getVPN(i));
		}
		for (Enumeration<Integer> it = VMKernel.usedSlot.keys(); it
				.hasMoreElements();)
		{
			int key = it.nextElement();
			if (processID == VMKernel.decodePID(key))
			{
				VMKernel.freeSlots.add(VMKernel.usedSlot.remove(key));
				VMKernel.swapedPage.remove(key);
			}
		}
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause)
	{
		switch (cause)
		{
		case Processor.exceptionTLBMiss:
			if (!VMKernel.handleTLBMiss(
					processID,
					Processor.pageFromAddress(Machine.processor().readRegister(
							Processor.regBadVAddr))))
				super.handleException(cause);
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	public TranslationEntry loadSection(int vpn, int ppn)
	{
		CoffSection cs = coff.getSection(pageSectionNum[vpn]);
		TranslationEntry te = new TranslationEntry(vpn, ppn, true,
				cs.isReadOnly(), false, false);
		cs.loadPage(pageSectionOffset[vpn], ppn);
		return te;
	}

	private static final char dbgVM = 'v';

	static boolean dbgUnload = false;

	// forlazyload
	int codePages = 0;

	/**
	 * Which section the vpn in.
	 */
	int pageSectionNum[];

	/**
	 * Which offset the vpn in the section is.
	 */
	int pageSectionOffset[];
}
