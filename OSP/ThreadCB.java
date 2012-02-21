package osp.Threads;
import java.util.Vector;
import java.util.Enumeration;
import osp.Utilities.*;
import osp.IFLModules.*;
import osp.Tasks.*;
import osp.EventEngine.*;
import osp.Hardware.*;
import osp.Devices.*;
import osp.Memory.*;
import osp.Resources.*;

/**
   ThreadCB.java
   Connor Leonhardt
   connor.leonhardt@gmail.com
   February 21, 2012
**/

/**
   This class is responsible for actions related to threads, including
   creating, killing, dispatching, resuming, and suspending threads.

   @OSPProject Threads
*/
public class ThreadCB extends IflThreadCB 
{
    //create readyQueue list
    static GenericList readyQueue;

    /**
       The thread constructor. Must call 

       	   super();

       as its first statement.

       @OSPProject Threads
    */
    public ThreadCB()
    {
        super(); //Constructor
	readyQueue = new GenericList();

    }

    /**
       This method will be called once at the beginning of the
       simulation. The student can set up static variables here.
       
       @OSPProject Threads
    */
    public static void init()
    {
        // your code goes here
    }

    /** 
        Sets up a new thread and adds it to the given task. 
        The method must set the ready status 
        and attempt to add thread to task. If the latter fails 
        because there are already too many threads in this task, 
        so does this method, otherwise, the thread is appended 
        to the ready queue and dispatch() is called.

	The priority of the thread can be set using the getPriority/setPriority
	methods. However, OSP itself doesn't care what the actual value of
	the priority is. These methods are just provided in case priority
	scheduling is required.

	@return thread or null

        @OSPProject Threads
    */
    static public ThreadCB do_create(TaskCB task)
    {
	//check if task is null
	//if true, call dispatcher and return null
	if (task == null)
	{
		ThreadCB.dispatch();
		return null;
	}

	//checks thread count against MaxThreadsPerTask
	//if true, call dispatcher and return null
	//else, creates thread
	if (task.getThreadCount() >= MaxThreadsPerTask)
	{
		ThreadCB.dispatch();
		return null;
	}

	//create new Thread Object
	ThreadCB newThread = new ThreadCB();

	//set thread to task
	if (task.addThread(newThread) == GlobalVariables.FAILURE)
		return null;

	//set task to thread
	newThread.setTask(task);

	//set priority
	newThread.setPriority(task.getPriority());

	//set status
	newThread.setStatus(ThreadReady);

	//append to readyQueue
	readyQueue.append(newThread);

	//call dispatcher and return thread
	ThreadCB.dispatch();
	return newThread;
	
    }

    /** 
	Kills the specified thread. 

	The status must be set to ThreadKill, the thread must be
	removed from the task's list of threads and its pending IORBs
	must be purged from all device queues.
        
	If some thread was on the ready queue, it must removed, if the 
	thread was running, the processor becomes idle, and dispatch() 
	must be called to resume a waiting thread.
	
	@OSPProject Threads
    */
    public void do_kill()
    {
	//get task
	TaskCB task = this.getTask();
	
	//get status of thread
	switch(this.getStatus())
	{
		//if status ThreadKill: break
		case ThreadKill:
			break;

		//if status ThreadReady: remove from readyQueue
		case ThreadReady:
			readyQueue.remove(this);
			break;

		//if status ThreadRunning: preempt
		case ThreadRunning:
			MMU.getPTBR().getTask().setCurrentThread(null);
			MMU.setPTBR(null);
			break;

		//loop through device table to purge any IORB associated with this thread
		default://ThreadWaiting at any level
			for (int i=0; i < Device.getTableSize(); i++)
				Device.get(i).cancelPendingIO(this);
			break;
	}

	//set status to ThreadKill
	this.setStatus(ThreadKill);

	//remove task from thread
	task.removeThread(this);

	//release all resources
	ResourceCB.giveupResources(this);

	//call dispatch
	ThreadCB.dispatch();

	//check if task has any threads left. if not, kill task
	if (task.getThreadCount() == 0)
		task.kill();

    }

    /** Suspends the thread that is currenly on the processor on the 
        specified event. 

        Note that the thread being suspended doesn't need to be
        running. It can also be waiting for completion of a pagefault
        and be suspended on the IORB that is bringing the page in.
	
	Thread's status must be changed to ThreadWaiting or higher,
        the processor set to idle, the thread must be in the right
        waiting queue, and dispatch() must be called to give CPU
        control to some other thread.

	@param event - event on which to suspend this thread.

        @OSPProject Threads
    */
    public void do_suspend(Event event)
    {
        //get thread status
	switch(this.getStatus())
	{
		case ThreadKill:
		case ThreadReady:
			return;

		//if running, suspend it
		case ThreadRunning:
			event.addThread(MMU.getPTBR().getTask().getCurrentThread());
			MMU.getPTBR().getTask().getCurrentThread().setStatus(ThreadWaiting);
			MMU.getPTBR().getTask().setCurrentThread(null);
			MMU.setPTBR(null);
			break;

		default://thread waiting
			event.removeThread(this);
			this.setStatus(this.getStatus() + 1);
			event.addThread(this);
			break;
	}

	//call dispatcher
	ThreadCB.dispatch();

    }

    /** Resumes the thread.
        
	Only a thread with the status ThreadWaiting or higher
	can be resumed.  The status must be set to ThreadReady or
	decremented, respectively.
	A ready thread should be placed on the ready queue.
	
	@OSPProject Threads
    */
    public void do_resume()
    {
        switch(this.getStatus())
	{
		case ThreadKill:
		case ThreadReady:
			return;

		case ThreadWaiting:
			this.setStatus(ThreadReady);
			readyQueue.append(this);
			break;

		default:
			this.setStatus(this.getStatus()-1);
			break;
	}

	ThreadCB.dispatch();

    }

    /** 
        Selects a thread from the run queue and dispatches it. 

        If there is just one theread ready to run, reschedule the thread 
        currently on the processor.

        In addition to setting the correct thread status it must
        update the PTBR.
	
	@return SUCCESS or FAILURE

        @OSPProject Threads
    */
    public static int do_dispatch()
    {
	//get the first in queue
	ThreadCB thread = (ThreadCB) readyQueue.removeHead();

	//Context Switching
	if(MMU.getPTBR() != null && thread != null)
	{
		//thread running, preempt
		readyQueue.append(MMU.getPTBR().getTask().getCurrentThread());
		MMU.getPTBR().getTask().getCurrentThread().setStatus(ThreadReady);
		MMU.getPTBR().getTask().setCurrentThread(null);
		MMU.setPTBR(null);

		//dispatch
		thread.setStatus(ThreadRunning);
		MMU.setPTBR(thread.getTask().getPageTable());
		thread.getTask().setCurrentThread(thread);
	}
	else if(MMU.getPTBR() != null && thread == null)
	{
		//current thread continues on cpu
		return SUCCESS;
	}
	else if(MMU.getPTBR() == null && thread != null)
	{
		//put thread on cpu
		thread.setStatus(ThreadRunning);
		MMU.setPTBR(thread.getTask().getPageTable());
		thread.getTask().setCurrentThread(thread);
	}
	else if(MMU.getPTBR() == null && thread == null)
	{
		return FAILURE;
	}

	return FAILURE;

    }

    /**
       Called by OSP after printing an error message. The student can
       insert code here to print various tables and data structures in
       their state just after the error happened.  The body can be
       left empty, if this feature is not used.

       @OSPProject Threads
    */
    public static void atError()
    {
        // your code goes here

    }

    /** Called by OSP after printing a warning message. The student
        can insert code here to print various tables and data
        structures in their state just after the warning happened.
        The body can be left empty, if this feature is not used.
       
        @OSPProject Threads
     */
    public static void atWarning()
    {
        // your code goes here

    }

    public static void removeKilled()
    {
	Enumeration ready = readyQueue.forwardIterator();
	ThreadCB thread;

	while(ready.hasMoreElements())
	{
		thread = (ThreadCB) ready.nextElement();

		if(thread.getStatus() == ThreadKill)
			readyQueue.remove(thread);
	}
    }

    /*
       Feel free to add methods/fields to improve the readability of your code
    */

}

/*
      Feel free to add local classes to improve the readability of your code
*/