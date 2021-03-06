package net.foxopen.fox.module;


import com.google.common.collect.Multimap;
import net.foxopen.fox.XFUtil;
import net.foxopen.fox.dom.DOM;
import net.foxopen.fox.dom.DOMList;
import net.foxopen.fox.dom.xpath.saxon.StoredXPathResolver;
import net.foxopen.fox.ex.ExDoSyntax;
import net.foxopen.fox.ex.ExInternal;
import net.foxopen.fox.ex.ExModule;
import net.foxopen.fox.ex.ExTooFew;
import net.foxopen.fox.ex.ExTooMany;
import net.foxopen.fox.module.datadefinition.ImplicatedDataDefinition;
import net.foxopen.fox.module.parsetree.ParseTree;
import net.foxopen.fox.module.parsetree.presentationnode.BufferPresentationNode;
import net.foxopen.fox.module.serialiser.HtmlDoctype;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class State implements Validatable {
  /** The module that this state exists in **/
  private final Mod mModule;

  /** The name of this state */
  private final String mStateName;

  /** The mStateTitle of the state */
  private final String mStateTitle;

  /** Doctype expected to be output from this state if HTML */
  private final HtmlDoctype mDocumentType;

  /** The attributes for the html presentation for a specific state **/
  private final Map<String, String> mStateAttr2Value;

  /** The state actions **/
  private final Map<String, ActionDefinition> mActionNamesToDefinitions;

  /** Map of buffer names to pre-parsed Presentation Nodes */
  private final Map<String, BufferPresentationNode> mParsedBuffers;

  /** Default set-page buffer */
  private final BufferPresentationNode mSetPageBuffer;

  private final Multimap<AutoActionType, ActionDefinition> mAutoActionMultimap;

  private final StoredXPathResolver mStoredXPathResolver;

  private final List<ImplicatedDataDefinition> mImplicatedDataDefinitions;

  private State(Mod pModule, String pStateName, String pStateTitle, HtmlDoctype pDocType, Map<String, String> pStateAttributes,
                Map<String, ActionDefinition> pActionNamesToDefinitions, Map<String, BufferPresentationNode> pParsedBuffers,
                BufferPresentationNode pSetPageBuffer, Multimap<AutoActionType, ActionDefinition> pAutoActionMultimap,
                StoredXPathResolver pStoredXPathResolver, List<ImplicatedDataDefinition> pImplicatedDataDefinitions)  {
    mModule = pModule;
    mStateName = pStateName;
    mStateTitle = pStateTitle;
    mDocumentType = pDocType;
    mStateAttr2Value = pStateAttributes;
    mActionNamesToDefinitions = pActionNamesToDefinitions;
    mParsedBuffers = pParsedBuffers;
    mSetPageBuffer = pSetPageBuffer;
    mAutoActionMultimap = pAutoActionMultimap;
    mStoredXPathResolver = pStoredXPathResolver;
    mImplicatedDataDefinitions = pImplicatedDataDefinitions;
  }

  public static State createState(Mod pModule, DOM pMetaData) throws ExModule, ExDoSyntax {
    if (pModule == null) {
      throw new ExInternal("State class error: the constructor was passed a Mod reference which was a null");
    }

    String lStateName = pMetaData.getAttr("name");
    if (lStateName == null || lStateName.trim().length() == 0) {
      throw new ExModule("State class error: No name attribute found for a state in module \"" + pModule.getName() + "\".");
    }

    String lStateTitle = XFUtil.nvl(pMetaData.getAttr("title"));

    // Pre-parse state level buffers
    Map<String, BufferPresentationNode> lParsedBuffers = new HashMap<String, BufferPresentationNode>();
    DOMList lBufferDOMs = pMetaData.getUL("fm:presentation/fm:set-buffer");
    for (DOM lSetBufferDOM : lBufferDOMs) {
      ParseTree lBufferParseTree = new ParseTree(lSetBufferDOM);
      BufferPresentationNode lBuffer = (BufferPresentationNode)lBufferParseTree.getRootNode();
      lParsedBuffers.put(lBuffer.getName(), lBuffer);
    }

    // Pre-parse the page buffer for the state
    BufferPresentationNode lSetPageBuffer = null;
    HtmlDoctype lDocumentType = null;
    try {
      DOM lSetPageDOM = pMetaData.get1E("fm:presentation/fm:set-page");
      ParseTree lStateBufferParseTree = new ParseTree(lSetPageDOM);
      lSetPageBuffer = (BufferPresentationNode)lStateBufferParseTree.getRootNode();
      lSetPageBuffer.setName("set-page");

      // Get doctype for HTML output, if specified
      lDocumentType = HtmlDoctype.getByNameOrNull(lSetPageDOM.getAttrOrNull("document-type"));
    }
    catch (ExTooFew e){
      /* ignore */
    }
    catch (ExTooMany e){
      throw new ExModule("A module state cannot have more than one set-page defined: '" + lStateName + "'");
    }

    // Parse the display attributes for the state, overriding ones defined at module level
    Map<String, String> lStateAttributes = new HashMap<>(pModule.getModuleAttributes());
    DOMList lDisplayAttributes = pMetaData.getUL("fm:presentation/fm:display-attr-list/fm:attr");
    for (DOM lDisplayAttribute : lDisplayAttributes) {
      lStateAttributes.put(lDisplayAttribute.getAttr("name"), XFUtil.nvl(lDisplayAttribute.value()));
    }

    // Passe the state implicated data definitions
    DOMList lImplicatedDataDefinitionsList = pMetaData.getUL("fm:presentation/fm:implicated-data-definition-list/fm:data-definition");
    List<ImplicatedDataDefinition> lImplicatedDataDefinitions = new ArrayList<>(lImplicatedDataDefinitionsList.size());
    lImplicatedDataDefinitions.addAll(lImplicatedDataDefinitionsList.stream().map(ImplicatedDataDefinition::new).collect(Collectors.toList()));

    // Parse the state actions
    DOMList lActionList = pMetaData.getUL("fm:action-list/fm:action");
    // Process Action list into hashtable of XDo Objects
    Multimap<AutoActionType, ActionDefinition> lAutoActionMultimap = AutoActionType.cloneAutoActionMultimap(pModule.getAutoActionMultimap());
    HashMap<String, ActionDefinition> lActionNamesToDefinitions = new HashMap<>();
    for(DOM lAction : lActionList) {
      ActionDefinition lActionDefinition = ActionDefinition.createActionDefinition(lAction, pModule, lStateName);
      String lActionName = lActionDefinition.getActionName();

      if(lActionDefinition.isAutoAction()) {
        //If this is an auto action add it to the map, potentially overriding any auto action with the same name from the module.
        //Guava quirk: putting into a multimap does not replace existing key/value pairs; we must remove first
        lAutoActionMultimap.remove(lActionDefinition.getAutoActionType(), lActionDefinition);
        lAutoActionMultimap.put(lActionDefinition.getAutoActionType(), lActionDefinition);
      }
      else {
        lActionNamesToDefinitions.put(lActionName, lActionDefinition);
      }
    }

    //Create a stored XPath resolver which delegates to the module definition if necessary
    StoredXPathResolver lXPathResolver = ModuleStoredXPathResolver.createFromDOMList(pMetaData.getUL("fm:xpath-list/fm:xpath"), pModule.getStoredXPathResolver());

    return new State(pModule, lStateName, lStateTitle, lDocumentType, lStateAttributes, lActionNamesToDefinitions, lParsedBuffers, lSetPageBuffer, lAutoActionMultimap, lXPathResolver, lImplicatedDataDefinitions);
  }

  /**
  * The programmatic name of the state.
  *
  * @return the state's programmatic name.
  */
  public String getName() {
    return mStateName;
  }

  /**
   * The human-readable mStateTitle of the state. This may be
   * displayed to the end user.
   *
   * @return the state's mStateTitle.
   */
  public String getTitle() {
    return mStateTitle;
  }

  public Map<String, ActionDefinition> getActionDefinitionMap() {
    return mActionNamesToDefinitions;
  }

  /**
   * Gets the auto actions defined in this state (and possibly its containing module) for the given auto action type.
   * The Collection will be ordered according to the ordering defined in the {@link AutoActionType} helper methods.
   * @param pAutoActionType
   * @return
   */
  public Collection<ActionDefinition> getAutoActions(AutoActionType pAutoActionType) {
    return mAutoActionMultimap.get(pAutoActionType);
  }

  public final ActionDefinition getActionByName(String pActionName)
  throws ExInternal {
    // Internal validation
    if(XFUtil.isNull(pActionName)) {
      throw new ExInternal("Null/blank value passed to getActionByName, perhaps your element has namespace:run on it but no namepsace:action");
    }

    // Extract state/action name pair
    StringBuffer  lStatePath = new StringBuffer(pActionName);
    String lActionName = XFUtil.pathPopTail(lStatePath);
    String lStateName = lStatePath.toString();
    ActionDefinition lActionDefinition;

    // When state/action pair specified get action from that state
    if(lStateName.length()>0) {
      return mModule.getState(lStateName).mActionNamesToDefinitions.get(lActionName);
    }
    // Otherwise search for action first in this state, then in module
    else {
      lActionDefinition = mActionNamesToDefinitions.get(pActionName);
      if (lActionDefinition == null) {
        lActionDefinition = mModule.getActionDefinitionMap().get(pActionName);
      }
    }

    if(lActionDefinition!=null) {
      return lActionDefinition;
    }

    throw new ExInternal("Action "+pActionName+" could not be found in state "+lStateName+" or module-level action-list");
  }

  /**
  * Called to allow the component to validate its syntax and structure.
  *
  * <p>For the State component, it is valid if, and only if, and embodied
  * actions are valid.
  *
  * @param module the module where the component resides
  * @throws ExInternal if the component syntax is invalid.
  */
  public void validate(Mod module)
  throws ExInternal {
    for (ActionDefinition lActionDefinition : mActionNamesToDefinitions.values()) {
      lActionDefinition.validate(module);
    }

    for(ActionDefinition lActionDefinition : mAutoActionMultimap.values()) {
      lActionDefinition.validate(module);
    }
  }

  /**
   * Return a pre parse Presentation Node for a named buffer in this module
   * @param pBufferName Name of the buffer to return
   * @return Pre-parsed Presentation Node
   */
  public BufferPresentationNode getParsedBuffer(String pBufferName) {
    return mParsedBuffers.get(pBufferName);
  }

  /**
   * Get the default buffer for this module
   * @return Pre-parsed Presentation Node
   */
  public BufferPresentationNode getSetPageBuffer() {
    return mSetPageBuffer;
  }

  public Map<String, String> getStateAttributes() {
    return mStateAttr2Value;
  }

  public HtmlDoctype getDocumentType() {
    return mDocumentType;
  }

  /**
   * Gets the StoredXPathResolver for this state, which delegates to its parent module if necessary.
   * @return Contextual StoredXPathResolver.
   */
  public StoredXPathResolver getStoredXPathResolver() {
    return mStoredXPathResolver;
  }

  public List<ImplicatedDataDefinition> getImplicatedDataDefinitions() {
    return mImplicatedDataDefinitions;
  }
}
