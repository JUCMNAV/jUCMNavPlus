package seg.jUCMNav.model.commands.Slicing;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Stack;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.EditPartViewer;
import org.eclipse.gef.commands.Command;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.UrnEditor;
import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.JUCMNavCommand;
import seg.jUCMNav.model.util.URNElementFinder;
import seg.jUCMNav.views.wizards.importexport.jUCMNavLoader;
import ucm.map.EndPoint;
import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.RespRef;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;

/**
 *   This Command performs Backward Static Prevslicing given a respRef in the model
 * 
 * @author Talhaj
 * 
 */

public class StaticSlicingCommand extends Command implements JUCMNavCommand {
	 private EObject Criterion;
	    public static SlicingAlg Prevslicing=null;
	    public SlicingAlg slicing;
	    public String OutPutFile;
	    public URNspec urn;
	    public UCMNavMultiPageEditor  editor;
	  public static StaticSlicingCommand prevCommand=null;
	 public EditPartViewer viewer;
	 public Boolean removeType;
	 public String fileName;
	  private ArrayList<String> criterionVariables=new ArrayList<String>();
	  /**
	   * Creates a new backward slicing command
	   * @param Criterion a resp-ref as a slicing criterion
	   * @param criterionVariables criterion variables related to the slicing criterion
	   */
	  public StaticSlicingCommand(EObject Criterion, ArrayList<String> criterionVariables,Boolean removeType,String fileName)
	  {  
	        this.Criterion = Criterion;
	        this.criterionVariables=criterionVariables;
	       this.removeType=removeType;
	       this.fileName=fileName;
	       
	        setLabel("Backward static slicing"); //$NON-NLS-1$
	       
           
	    }
	
	    /**
	     * @see org.eclipse.gef.commands.Command#canExecute()
	     */
	    public boolean canExecute() {
	    	
	    	
	    	return true;
	        
	    }



/**
 * @see org.eclipse.gef.commands.Command#execute()
 */
public void execute() {
  testPreConditions();
	redo();
	
	
   
}

/**
 * 
 * @see org.eclipse.gef.commands.Command#redo()
 */
public void redo() {
	
	IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
	   editor=(UCMNavMultiPageEditor) page.getActiveEditor();
	   //saving the current editor before copying the file
      editor.doSave(new NullProgressMonitor());
	   urn=	editor.getModel();
	   //check if slicing with variables or with no variables
	  
		   //if removal approach is chosen
		   if(removeType)
		   {
		sliceWithRemove();
		// run slicing Algorithm
	     runSlicingAlg();

		   }
		   //otherwise coloring is chosen,therefore no need for output file 
	   else
		   runSlicingAlg();
	//newUCMFile();	   
	  }
/**
 * tests the creation and loading of new ucm file 
 */
public void newUCMFile()
{
	
	IWorkbenchPage workbenchPage=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
	 jUCMNavLoader loader = new jUCMNavLoader(workbenchPage, new Shell());
	 //IWorkbench workbench= PlatformUI.getWorkbench();
     	//IWorkbenchPage page=	 workbench.getActiveWorkbenchWindow().getActivePage();
     	
    //     UCMNavMultiPageEditor editor = (UCMNavMultiPageEditor) page.getActiveEditor();
     	
     	
    	 //IEditorInput input=editor.getEditorInput();
    	 //IFile file = ((IFileEditorInput)input).getFile();
    	 //String sourceFile=file.getLocation().toOSString();
    	
	 String fileName="testNewFile.jucm";
     String containerName="\\hh";
     //GeneralPreferencePage.setNewUCM(true);
     // fileName is added as parameter of getNewURNspec to be able to name a concern after the name of .jucm file
     try {
    	 //loader.createAndOpenFile(fileName, containerName, ModelCreationFactory.getNewURNspec(fileName.substring(0,fileName.lastIndexOf("."))));
		loader.createAndOpenFile(fileName, containerName, ModelCreationFactory.getNewURNspec(fileName.substring(0,fileName.lastIndexOf("."))), false, true);
	} catch (InvocationTargetException e) {
		// TODO Auto-generated catch block
		//e.printStackTrace();
		//System.out.println("loading Error");
	}
    
}
/**
 * executes slicing with removal approach, this requires creating a new ucm file,opening it, and then 
 * executing the slicing algorithm by calling {@link #runSlicingAlg(IFile, IURNDiagram)}  
 */
public void sliceWithRemove()
{
	IURNDiagram targetMap=null;
	String ID=null;
	String MapID=null;
	IURNDiagram mapNC=null;
	String idSource=null,idTarget=null;
	if(Criterion instanceof PathNode)
	{
	  ID=((PathNode)Criterion).getId();
	  UCMmap map= (UCMmap) (((PathNode) Criterion).getDiagram());
	   MapID=map.getId();
	}
    //otherwise criterion is a node connection
	else
	{
		mapNC=((NodeConnection) Criterion).getDiagram();
		idSource=((PathNode)((NodeConnection) Criterion).getSource()).getId();
		idTarget=((PathNode)((NodeConnection) Criterion).getTarget()).getId();
		MapID=((UCMmap)mapNC).getId();
	}
	File output=copyfiles(fileName);
	
	
	
	IPath location= Path.fromOSString(output.getAbsolutePath()); 
	IFile iFile=ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(location);
	//refresh workspace before opening the new ucm file
	try {
		iFile.refreshLocal(IResource.DEPTH_ZERO, null);
	} catch (CoreException e1) {
		// TODO Auto-generated catch block
		e1.printStackTrace();
	}
	
	      //Open the editor
	IWorkbenchPage   page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
	  UCMNavMultiPageEditor prevEditor=(UCMNavMultiPageEditor) page.getActiveEditor();
	 
    // IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(output.getName());
     try {
   	  IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().findEditor("seg.jUCMNav.MainEditor");
		  editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(iFile), desc.getId());
	
		 urn=	editor.getModel();
	
	//set active page to the diagram to which the criterion belong
	for(Object diagram:urn.getUrndef().getSpecDiagrams())
	{
		     if(diagram instanceof UCMmap &&  ((UCMmap) diagram).getId().equals(MapID))
		     {
		    	 targetMap=(IURNDiagram)diagram;
		    	 editor.setActivePage((IURNDiagram)diagram);
		    	 viewer = ((UrnEditor) editor.getActiveEditor()).getGraphicalViewer();
		    	 if(Criterion instanceof RespRef)
		    	Criterion= (RespRef) URNElementFinder.find(urn,ID );
		    	 else if(Criterion instanceof EndPoint)
		    		 Criterion= (EndPoint) URNElementFinder.find(urn,ID );
		    	 //otherwise, the criterion is a node connection
		    	 else
		    	 {
		    		 mapNC= URNElementFinder.findMap(urn, MapID);
		    		 Criterion=(EObject) URNElementFinder.findConnection(mapNC, idSource, idTarget);
		    	 
		    	 }
		    	editor.doSave(new NullProgressMonitor());
		     }
	}
	} catch (PartInitException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	
     
}
public void runSlicingAlg()
{
	NodeConnection startingNC=null;
	NodeConnection forwardStartingNC=null;
	if(Criterion instanceof PathNode)
	 startingNC=(NodeConnection) ((PathNode)Criterion).getPred().get(0);
	//otherwise it's a node connection
	else
	{
		//get pred link after orfork,timer,or waitingplace
		startingNC= (NodeConnection) ((NodeConnection) Criterion).getSource().getPred().get(0);
	}
		// used for concurrency forward check
	 if(Criterion instanceof RespRef)
		 forwardStartingNC=(NodeConnection) ((PathNode)Criterion).getSucc().get(0);
	
		slicing=new SlicingAlg(startingNC,criterionVariables);
		//setting required attributes 
		slicing.setCriterion(Criterion);
		slicing.setViewer(viewer);
		slicing.setUrn(urn);
		slicing.setEditor(editor);
		if(Criterion instanceof PathNode)
		slicing.setTargetMap(((PathNode)Criterion).getDiagram());
		else
			slicing.setTargetMap(((NodeConnection)Criterion).getDiagram());
		SlicingAlg.concurrencyGroups.clear();
		SlicingAlg.unrelatedStubINS.clear();
		SlicingAlg.maps.clear();
		SlicingAlg.startpoints.clear();
		SlicingAlg.UnrelatedOrForkbranches.clear();
		SlicingAlg.allNotRelevantRespRefList.clear();
		SlicingAlg.allRelevantRespRefList.clear();
		slicing.visitedNodes.clear();
		slicing.visitedNodes.add(Criterion);
		 
		if(Criterion instanceof PathNode)
		{
			slicing.setUrn(((PathNode)Criterion).getDiagram().getUrndefinition().getUrnspec());
			slicing.maps.add((UCMmap)((PathNode)Criterion).getDiagram());
		}
			else
			{
			slicing.setUrn(((NodeConnection)Criterion).getDiagram().getUrndefinition().getUrnspec());
			slicing.maps.add((UCMmap)((NodeConnection)Criterion).getDiagram());
			//when criterion is a node connection, add unrelated branches of the source
			ArrayList<NodeConnection> temp=new ArrayList<NodeConnection>();
			PathNode source=(PathNode) ((NodeConnection)Criterion).getSource();
			for(Object link:source.getSucc())
				temp.add((NodeConnection) link);
			//remove the current node connection
			temp.remove((NodeConnection)Criterion);
			//add to the unrelatedOrFork list
			for(NodeConnection nc:temp)
				if(!slicing.UnrelatedOrForkbranches.contains(nc))
				slicing.UnrelatedOrForkbranches.add(nc);
			//add source to visited nodes list
			slicing.visitedNodes.add((EObject)source);
			}
			
		 slicing.emptyRespRef.clear();
		 SlicingAlg.criterionExcludedNC.clear();
		 SlicingAlg.concurrencyNodeConnections.clear();
		SlicingAlg.criterionForwardBranches.clear();
		SlicingAlg.allPaths.clear();
		 ArrayList<EObject> pathVisitedJoins=new ArrayList<EObject>();

		// The dependency analysis below (forwardCheck_criterion + executeAlg, which
		// recurses thousands of times through analizeCondition/analizeStatement/
		// Parsing.getVariables) is what used to freeze the UI for seconds and trip
		// org.eclipse.ui.monitoring. Run it off the UI thread under a cancellable
		// ProgressMonitorDialog (issue #9, Option A). It is pure model analysis --
		// it touches no SWT/figure state; only the result application below
		// (removeUnrelatedElements / coloring) does, and that stays on the UI thread
		// after the dialog returns. A cancelled run skips the application entirely,
		// so the model is left untouched.
		final NodeConnection fStartingNC = startingNC;
		final NodeConnection fForwardStartingNC = forwardStartingNC;
		final ArrayList<EObject> fPathVisitedJoins = pathVisitedJoins;
		if (runDependencyAnalysisUnderProgress(fStartingNC, fForwardStartingNC, fPathVisitedJoins))
			return; // cancelled or errored -> leave the model untouched

		//Now check whether remove approach  or coloring is selected
		if(removeType)
			if(criterionVariables!=null)
		slicing.removeUnrelatedElements(true);
			else
				slicing.removeUnrelatedElements(false);
		//otherwise coloring is selected
		else
		{
			slicing.coloring();
		}

	    Prevslicing=slicing;

	      testPostConditions();

}

/**
 * Runs the (potentially long) dependency analysis under a cancellable modal
 * {@link ProgressMonitorDialog} on a worker thread, so the workbench stays
 * responsive instead of freezing (issue #9, Option A). Returns {@code true} if
 * the user cancelled or the analysis threw, in which case the caller must NOT
 * apply any result -- the model is left unchanged. Falls back to running inline
 * when there is no active shell (e.g. headless test runs).
 */
private boolean runDependencyAnalysisUnderProgress(final NodeConnection startingNC,
		final NodeConnection forwardStartingNC, final ArrayList<EObject> pathVisitedJoins) {

	Shell shell = null;
	if (PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null)
		shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();

	if (shell == null) {
		// No UI context: run inline, no dialog, no cancellation. Behaviour unchanged.
		executeDependencyAnalysis(startingNC, forwardStartingNC, pathVisitedJoins);
		return false;
	}

	try {
		new ProgressMonitorDialog(shell).run(true, true, new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
				monitor.beginTask("Computing static slice…", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
				slicing.setSliceMonitor(monitor);
				try {
					executeDependencyAnalysis(startingNC, forwardStartingNC, pathVisitedJoins);
				} catch (OperationCanceledException oce) {
					throw new InterruptedException();
				} finally {
					slicing.setSliceMonitor(null);
					monitor.done();
				}
			}
		});
	} catch (InterruptedException cancelled) {
		return true; // user pressed Cancel
	} catch (InvocationTargetException ite) {
		// analysis failed on the worker thread; log and bail without touching the model
		if (ite.getCause() != null)
			ite.getCause().printStackTrace();
		else
			ite.printStackTrace();
		return true;
	}
	return false;
}

/**
 * The pure-model dependency analysis lifted out of {@link #runSlicingAlg()} so it
 * can run on a worker thread (issue #9). The logic is identical to the original
 * inline body: it only reads the model and fills the SlicingAlg result lists, and
 * touches no SWT state. {@code executeAlg} checks the slice monitor at every
 * recursion entry, so this aborts promptly via OperationCanceledException when the
 * user cancels.
 */
private void executeDependencyAnalysis(NodeConnection startingNC, NodeConnection forwardStartingNC,
		ArrayList<EObject> pathVisitedJoins) {

		 if(Criterion instanceof RespRef)
		 {
			 slicing.forwardNodeConnections.clear();
		 slicing.forwardCheck_criterion(forwardStartingNC, pathVisitedJoins);
			if(!slicing.forwardNodeConnections.isEmpty())
			{
				//if no variables are there, no need to create concurrency groups
				if(criterionVariables!=null)
				slicing.Concurreny_CreateGroup(slicing.getStartingNodeConnection());
				Iterator<NodeConnection> it=slicing.forwardNodeConnections.iterator();
				while(it.hasNext())
				{
					NodeConnection nc=it.next();
					//we add the criterion forward branches to run them after running the basic instance
					SlicingAlg.criterionForwardBranches.add(nc);
					SlicingAlg.concurrencyNodeConnections.add(nc);
				}
			}

           }
			//if no criterion variables are there, execute normal slicing algorithm
			if(criterionVariables!=null)
			{
		slicing.executeAlg(criterionVariables ,startingNC,new Stack<Stub>(),pathVisitedJoins,slicing.unrelatedTree,slicing.commonTree,false);
		SlicingAlg.allPaths.add(slicing);
			}
			//otherwise, execute the other form of traversal in which no dependencies are computed
			else
				slicing.executeAlg(startingNC,new Stack<Stub>(),pathVisitedJoins);
		if(Criterion instanceof RespRef)
		{
			//now we create and execute the criterion's forward concurrent slicing instances
			if(!SlicingAlg.criterionForwardBranches.isEmpty())
			{
				SlicingAlg sl;
			Iterator<NodeConnection> iter=SlicingAlg.criterionForwardBranches.iterator();
			while(iter.hasNext())
			{
				NodeConnection nc=iter.next();
				sl=new SlicingAlg(nc,criterionVariables);
				sl.setCriterion(Criterion);
				if(criterionVariables!=null)
				{
				sl.executeAlg(criterionVariables, nc, new Stack<Stub>(), new ArrayList<EObject>(),sl.unrelatedTree,sl.commonTree,false);
				SlicingAlg.allPaths.add(sl);
				}
				//otherwise, execute the other form of traversal in which no dependencies are computed
				else
					sl.executeAlg(nc, new Stack<Stub>(), new ArrayList<EObject>());
			}

			}
		}

		//handle concurrency
		//first remove inconsistency
		if(criterionVariables!=null)
		for(RespRef resp:SlicingAlg.allRelevantRespRefList)
			SlicingAlg.allNotRelevantRespRefList.removeAll(Collections.singleton(resp));

		if(criterionVariables!=null)
		{
		ConcurrencyHandler con=new ConcurrencyHandler();
		con.handleGroups(SlicingAlg.concurrencyGroups);
		}
}
/**
 * @see seg.jUCMNav.model.commands.JUCMNavCommand#testPreConditions()
 */
public void testPreConditions() {

	/*
	if(Prevslicing!=null)
	{
		Prevslicing.undoSlicing();
		Prevslicing=null;
		
		
	}
	*/
}

/**
 * @see org.eclipse.gef.commands.Command#undo()
 */
public void undo() {
	
	if(!removeType)
		slicing.Uncoloring();
  
}
/**
 * copy the current ucm file
 * @param name name of the file
 * @return the new ucm file
 */
public File copyfiles(String name)
{
	IWorkbench workbench= PlatformUI.getWorkbench();
	IWorkbenchPage page=	 workbench.getActiveWorkbenchWindow().getActivePage();
	 UCMNavMultiPageEditor editor = (UCMNavMultiPageEditor) page.getActiveEditor();
	
	 
	
	 IEditorInput input=editor.getEditorInput();
	 IFile file = ((IFileEditorInput)input).getFile();
	 String sourceFile=file.getLocation().toOSString();
	//String sourceFile=file.getFullPath().toOSString();
			 
	 String targetFile=sourceFile.substring(0, sourceFile.lastIndexOf("\\")+1);
	 targetFile+=name+".jucm";
	 //System.out.println("LOcation="+sourceFile+"\nnew file="+targetFile);
	File source=new File(sourceFile);
	File target=new File(targetFile);
	
	if(!target.exists())
	{
		try {
			Files.copy(source.toPath(), target.toPath());
			
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			
		}
	}
	
	else
	{
		int i=1;
		while (target.exists())
		{
			
			targetFile=sourceFile.substring(0, sourceFile.lastIndexOf("\\")+1);
			targetFile+=name+"("+i+").jucm";
			i++;
			target=new File(targetFile);
		}
		target=new File(targetFile);
		try {
			Files.copy(source.toPath(), target.toPath());
			
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			
		}
	}
		return target;

}
public void testPostConditions() {
	   // assert urn != null && urn.getUrndef() != null && respref != null : "post not null"; //$NON-NLS-1$
	    //assert urn.getUrndef().getResponsibilities().contains(respref) : "post resp not in model"; //$NON-NLS-1$
		
	}


}
