package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.vm.VMProcess;

import java.io.EOFException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;

import javax.net.ssl.SSLEngineResult.Status;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		processID = ++usedID;
		runningProcessNum++;
		allProcess.put(processID, this);
		int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		for (int i = 0; i < numPhysPages; i++)
			pageTable[i] = new TranslationEntry(i, i, true, false, false, false);
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;
		new UThread(this).setName(name).fork();

		return true;
	}
	
	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr
	 *            the starting virtual address of the null-terminated string.
	 * @param maxLength
	 *            the maximum number of characters in the string, not including
	 *            the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 *         found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @param offset
	 *            the first byte to write in the array.
	 * @param length
	 *            the number of bytes to transfer from virtual memory to the
	 *            array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		if (vaddr < 0)
			return 0;
		int amount = length;
		if (amount == 0) return 0;
		int vaddrS = vaddr;
        int vaddrE = vaddr + amount - 1;
        
        int vaddrSOff = Processor.offsetFromAddress(vaddrS);
        int vaddrEOff = Processor.offsetFromAddress(vaddrE);
        int vaddrSVPN = Processor.pageFromAddress(vaddrS);
        int vaddrEVPN = Processor.pageFromAddress(vaddrE);
        
        int memoryOffset = 0;
        int PPN = 0;
        boolean onePage = (vaddrSVPN == vaddrEVPN);
        
        boolean intStatus = Machine.interrupt().setStatus(false);
        //FirstyPage
        PPN = getPPN(vaddrSVPN);//here
        System.arraycopy(memory, Processor.makeAddress(PPN, vaddrSOff), data,
                        memoryOffset + offset, onePage ? amount : pageSize - vaddrSOff);
        memoryOffset += onePage ? amount : pageSize - vaddrSOff;
        //MidPage
        for (int i = vaddrSVPN + 1; i < vaddrEVPN; i++) 
        {
            PPN = getPPN(i);
            System.arraycopy(memory, Processor.makeAddress(PPN, 0), data,
                            memoryOffset + offset, pageSize);
            memoryOffset += pageSize;
        }
        //LastPage
        if (!onePage) 
        {
            PPN = getPPN(vaddrEVPN);
            System.arraycopy(memory, Processor.makeAddress(PPN, 0), data,
                            memoryOffset + offset, vaddrEOff);
            memoryOffset += vaddrEOff+1;
        }
        Machine.interrupt().setStatus(intStatus);
		return memoryOffset;
	}

	public int getPPN(int vpn) 
	{
        pageTable[vpn].used = true;
        return pageTable[vpn].ppn;
	}
	
	
	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @param offset
	 *            the first byte to transfer from the array.
	 * @param length
	 *            the number of bytes to transfer from the array to virtual
	 *            memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);
		
		byte[] memory = Machine.processor().getMemory();
		if (vaddr < 0)
			return 0;
		int amount = length;
		if (amount == 0) return 0;
	    int vaddrS = vaddr;
	    int vaddrE = vaddr + amount - 1;
	    int vaddrSOff = Processor.offsetFromAddress(vaddrS);
	    int vaddrEOff = Processor.offsetFromAddress(vaddrE);
	    int vaddrSVPN = Processor.pageFromAddress(vaddrS);
	    int vaddrEVPN = Processor.pageFromAddress(vaddrE);
	
	    int memoryOffset = 0;
	    int ppn = 0;
	    boolean onePage = (vaddrSVPN == vaddrEVPN);
		
	    //FirstPage
	    boolean intStatus = Machine.interrupt().setStatus(false);
	    ppn = writePPN(vaddrSVPN);
	    if (ppn == -1) 
	    {
	        Machine.interrupt().setStatus(intStatus);
	        return memoryOffset;
	    }
	    System.arraycopy(data, memoryOffset + offset, memory, Processor.makeAddress(ppn, vaddrSOff), onePage ? amount : pageSize - vaddrSOff);
	    memoryOffset += onePage ? amount : pageSize - vaddrSOff;
	    //MidPage
	    for (int i = vaddrSVPN + 1; i < vaddrEVPN; i++) 
	    {
	        ppn = writePPN(i);
	        if (ppn == -1) 
	        {
	            Machine.interrupt().setStatus(intStatus);
	            return memoryOffset;
	        }
	        System.arraycopy(data, memoryOffset + offset, memory, Processor.makeAddress(ppn, 0), pageSize);
	        memoryOffset += pageSize;
	    }
	    //LastPage
	    if (!onePage) 
	    {
	        ppn = writePPN(vaddrEVPN);
	        if (ppn == -1) 
	        {
	            Machine.interrupt().setStatus(intStatus);
	            return memoryOffset;
	        }
	        System.arraycopy(data, memoryOffset + offset, memory, Processor
	                            .makeAddress(ppn, 0), vaddrEOff);
	        memoryOffset += vaddrEOff+1;
	    }
	    Machine.interrupt().setStatus(intStatus);
	    return memoryOffset;
	}
	
	public int writePPN(int vpn) 
	{
        if (pageTable[vpn].readOnly)
        	return -1;
        pageTable[vpn].used = true;
        pageTable[vpn].dirty = true;
        return pageTable[vpn].ppn;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	protected boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		} catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;
		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages() || numPages > UserKernel.freePages.size()) 
		{
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}
		
		// load sections
		int ppns[] = new int[numPages];
		for (int i = 0; i < numPages; ++i)
		{
			ppns[i] = UserKernel.freePages.removeFirst();
		}
		for (int s = 0; s < coff.getNumSections(); s++) 
		{
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");
			for (int i = 0; i < section.getLength(); i++) 
			{
				int vpn = section.getFirstVPN() + i;
				pageTable[vpn] = new TranslationEntry(vpn, ppns[vpn], true, section.isReadOnly(), false, false);
				section.loadPage(i, ppns[vpn]);
			}
		}
		
		for (int vpn = numPages - stackPages - 1; vpn < numPages; vpn++)
            pageTable[vpn] = new TranslationEntry(vpn, ppns[vpn], true, false, false, false);
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int i = 0; i < numPages; i++)
		{
			UserKernel.freePages.add(pageTable[i].ppn);
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < Processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		if (!isRootProcess)
             return -1;
		UserKernel.kernel.terminate();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}
	
	private int handleExit(int status) 
	{
		childProcessIDs.clear();
		coff.close();
		for (int i = 0; i < maxFileNumber+2; ++i)
		{
			if (allFiles.containsKey(i))
			{
				handleClose(i);
			}
		}
		unloadSections();
		//allProcess.remove(processID);
		runningProcessNum--;
		joinSemaphore.V();
		this.status = status;
		if (runningProcessNum == 0)
            UserKernel.kernel.terminate();
		UThread.finish();
		return 0;
	}
	
	private int handleExec(int name, int argc, int argv)
	{
		String execName = readVirtualMemoryString(name, maxFileNameLength);
		if (!execName.endsWith(".coff"))
            return -1;
        String args[] = new String[argc];
        byte[] buf = new byte[argc * 4];
        this.readVirtualMemory(argv, buf, 0, argc * 4);
        for (int i = 0; i < argc; i++) 
        {
            int startAddr = Lib.bytesToInt(buf, i * 4, 4);
            args[i] = readVirtualMemoryString(startAddr, maxFileNameLength);
        }
        UserProcess cp = newUserProcess();
        childProcessIDs.add(cp.processID);
        if (cp.execute(execName, args))
        {
            return cp.processID;
        }
        else
        {
        	allProcess.remove(cp.processID);
        	runningProcessNum--;
            return -1;
        }
    }
	
	private int handleJoin(int ID, int status)
	{
		if (!childProcessIDs.contains(ID))
		{
			return -1;
		}
	    UserProcess childProcess = allProcess.get(ID);
	    if (childProcess != null)
	    {
	    	childProcess.joinSemaphore.P();
	    	byte[] buf = Lib.bytesFromInt(childProcess.status);
	    	if (childProcess.status == -1)
	    	{
	    		writeVirtualMemory(status, buf);
	    		return 0;
	    	}
	    	writeVirtualMemory(status, buf);
	    }
	    return 1;
	}
	
	private int handleCreate(int name)
	{
		String sname = this.readVirtualMemoryString(name, maxFileNameLength);
		if (sname == null ||+ sname.length() > maxFileNameLength)
		{
			Lib.debug(dbgProcess, "Sorry, file name error!");
            return -1;
		}
		if (allFiles.size() >= maxFileNumber) 
		{
			Lib.debug(dbgProcess, "Sorry, you cannot creat more file!");
            return -1;
		}
		if (deleteFile.contains(sname)) 
		{
			Lib.debug(dbgProcess, "Sorry, cannot create a file that will be delete soon!");
            return -1;
		}

		int des = -1;
		for (int i = 2; i < maxFileNumber+2; ++i)
		{
			if (allFiles.get(i) == null)
			{
				des = i;
				break;
			}
		}
		if (des == -1)
		{
			Lib.debug(dbgProcess, "Sorry, you cannot creat more file!");
			return -1;
		}
		
		OpenFile openFile = UserKernel.fileSystem.open(sname, true);
		if (openFile == null)
		{
			Lib.debug(dbgProcess, "Sorry, cannot create file!");
            return -1;
		}
		
		allFiles.put(des, openFile);
		
		if (fileOpenNum.get(sname) == null)
            fileOpenNum.put(sname, 1);
		else
            fileOpenNum.put(sname, fileOpenNum.get(sname) + 1);
		
		return des;
	}
	
	private int handleOpen(int name)
	{
		String sname = this.readVirtualMemoryString(name, maxFileNameLength);
		if (sname == null || sname.length() > maxFileNameLength)
		{
			Lib.debug(dbgProcess, "Sorry, file name error!");
            return -1;
		}
		if (allFiles.size() >= maxFileNumber) 
		{
			Lib.debug(dbgProcess, "Sorry, you cannot open more file!");
            return -1;
		}
		if (deleteFile.contains(sname)) 
		{
			Lib.debug(dbgProcess, "Sorry, cannot open a file that will be delete soon!");
            return -1;
		}

		int des = -1;
		for (int i = 2; i < maxFileNumber+2; ++i)
		{
			if (allFiles.get(i) == null)
			{
				des = i;
				break;
			}
		}
		if (des == -1)
		{
			Lib.debug(dbgProcess, "Sorry, you cannot open more file!");
			return -1;
		}
		
		OpenFile openFile = UserKernel.fileSystem.open(sname, false);
		if (openFile == null)
		{
			Lib.debug(dbgProcess, "Sorry, cannot open file!");
            return -1;
		}
		allFiles.put(des, openFile);
		
		if (fileOpenNum.get(sname) == null)
            fileOpenNum.put(sname, 1);
		else
            fileOpenNum.put(sname, fileOpenNum.get(sname) + 1);
		return des;
	}
	
    private int handleRead(int fd, int buffer, int size) 
    {
    	OpenFile openFile = null;
    	if (fd == 0)
    	{
    		openFile = stdIn;
    	}
    	else if (fd == 1)
    	{
    		Lib.debug(dbgProcess, "Sorry, you cannot read in StdOut!");
    		return -1;
    	}
    	else
    	{
	        openFile = allFiles.get(fd);
    	}
    	if (openFile == null)
        {
    		Lib.debug(dbgProcess, "Sorry, you cannot read file!");
        	return -1;
        }
        byte buf[] = new byte[size];
        int len = openFile.read(buf, 0, size);
        len = this.writeVirtualMemory(buffer, buf, 0, len);
        return len;
    }

	private int handleWrite(int fd, int buffer, int size)
	{
    	OpenFile openFile = null;
    	if (fd == 1)
    	{
    		openFile = stdOut;
    	}
    	else if (fd == 0)
    	{
    		Lib.debug(dbgProcess, "Sorry, you cannot Write in StdIn!");
    		return -1;
    	}
    	else
    	{
	        openFile = allFiles.get(fd);
    	}
    	if (openFile == null)
        {
    		Lib.debug(dbgProcess, "Sorry, you cannot write file!");
        	return -1;
        }
        byte buf[] = new byte[size];
        int len = this.readVirtualMemory(buffer, buf, 0, size);
        len = openFile.write(buf, 0, len);
        return len;
	}
	
	private int handleClose(int fd)
	{
		OpenFile openFile = allFiles.get(fd);
		if (openFile == null)
		{
			Lib.debug(dbgProcess, "Sorry, you cannot close file!");
			return -1;
		}
		String name = openFile.getName();
		openFile.close();
		allFiles.remove(fd);
		fileOpenNum.put(name, fileOpenNum.get(name)-1);
		if (fileOpenNum.get(name) == 0) 
		{
            fileOpenNum.remove(name);
            if (deleteFile.contains(name)) 
            {
                    UserKernel.fileSystem.remove(name);
                    deleteFile.remove(name);
            }
		}
		return 0;
	}
	
	private int handleUnlink(int name)
	{
		String sname = this.readVirtualMemoryString(name, maxFileNameLength);
        if (sname == null || sname.length() > maxFileNameLength)
        {
        	Lib.debug(dbgProcess, "Sorry, file name error!");
        	return -1;
        }
        if (fileOpenNum.get(sname) == null) 
        {
            ThreadedKernel.fileSystem.remove(sname);
        }
        else 
            deleteFile.add(sname);
		return 0;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;
	
	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall
	 *            the syscall number.
	 * @param a0
	 *            the first syscall argument.
	 * @param a1
	 *            the second syscall argument.
	 * @param a2
	 *            the third syscall argument.
	 * @param a3
	 *            the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			handleExit(-1);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0), processor
							.readRegister(Processor.regA1), processor
							.readRegister(Processor.regA2), processor
							.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			handleExit(-1);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;
	
	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = Config.getInteger("Processor.numStackPages", 8);

	protected int initialPC;

	protected int initialSP;
	protected int argc;

	protected int argv;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
	
	//Author Add
	
	public boolean isRootProcess = false;
	
	private static final int maxFileNameLength = 256;
	private static final int maxFileNumber = 16;
	
	private Hashtable<Integer, OpenFile> allFiles = new Hashtable<Integer, OpenFile>();
	private static HashSet<String> deleteFile = new HashSet<String>();
	
	private static Hashtable<String, Integer> fileOpenNum = new Hashtable<String, Integer>();
	private static OpenFile stdIn = UserKernel.console.openForReading();
	private static OpenFile stdOut = UserKernel.console.openForWriting();
	protected int processID;
	private static int usedID = 0;
	public static Hashtable<Integer, UserProcess> allProcess = new Hashtable<Integer, UserProcess>();
	protected int status;
	protected LinkedList<Integer> childProcessIDs = new LinkedList<Integer>(); 
	private Semaphore joinSemaphore = new Semaphore(0);
	private static int runningProcessNum = 0;
}
