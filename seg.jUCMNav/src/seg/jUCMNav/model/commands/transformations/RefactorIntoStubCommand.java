package seg.jUCMNav.model.commands.transformations;

import java.util.Iterator;
import java.util.Vector;

import org.eclipse.gef.commands.CompoundCommand;

import seg.jUCMNav.Messages;
import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.IGlobalStackCommand;
import seg.jUCMNav.model.commands.create.AddPluginCommand;
import seg.jUCMNav.model.commands.create.CreateMapCommand;
import seg.jUCMNav.model.commands.transformations.internal.BindExtractedStubCommand;
import seg.jUCMNav.model.commands.transformations.internal.ExtractScopeIntoStubCommand;
import seg.jUCMNav.model.util.ICreateElementCommand;
import seg.jUCMNav.model.util.StubExtractionScope;
import ucm.map.PathNode;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;

public class RefactorIntoStubCommand extends CompoundCommand implements ICreateElementCommand, IGlobalStackCommand {

    private Vector startingPoints;
    private URNspec urn;
    private UCMmap addedMap;
    private Stub addedStub;

    public RefactorIntoStubCommand(URNspec urn, Vector startingPoints) {

        this.startingPoints = startingPoints;
        this.urn = urn;
        build();
    }

    protected void build() {
        StubExtractionScope extraction = new StubExtractionScope(startingPoints);
        if (extraction.isEmpty())
            return;

        // 1. the plug-in map the scope moves onto.
        CreateMapCommand createMap = new CreateMapCommand(urn);
        setAddedMap(createMap.getMap());
        getAddedMap().setName(Messages.getString("RefactorIntoStubCommand_ExtractedUCM")); //$NON-NLS-1$
        add(createMap);

        // 2. the stub that replaces it, sitting where the scope's centre of mass was.
        setAddedStub((Stub) ModelCreationFactory.getNewObject(urn, Stub.class));
        getAddedStub().setName(Messages.getString("RefactorIntoStubCommand_ExtractedUCM")); //$NON-NLS-1$

        int x = 0, y = 0;
        for (Iterator<PathNode> iter = extraction.getScope().iterator(); iter.hasNext();) {
            PathNode pn = iter.next();
            x += pn.getX();
            y += pn.getY();
        }
        x /= extraction.getScope().size();
        y /= extraction.getScope().size();

        // 3. move the scope across and rewire the boundary onto the stub. One in-path per
        // inbound connection and one out-path per outbound one, by construction.
        ExtractScopeIntoStubCommand extract = new ExtractScopeIntoStubCommand(extraction, getAddedMap(), getAddedStub(), x, y);
        add(extract);

        // 4. the plug-in binding, then the in/out bindings, which the extraction already knows.
        AddPluginCommand plugin = new AddPluginCommand(getAddedStub(), getAddedMap());
        add(plugin);
        add(new BindExtractedStubCommand(extract, plugin));
    }

    public void setAddedMap(UCMmap addedMap) {
        this.addedMap = addedMap;
    }

    public UCMmap getAddedMap() {
        return addedMap;
    }

    public void setAddedStub(Stub addedStub) {
        this.addedStub = addedStub;
    }

    public Stub getAddedStub() {
        return addedStub;
    }

    public Object getNewModelElement() {
        return getAddedStub();
    }

    public IURNDiagram getAffectedDiagram() {
        return getAddedMap();
    }
}
