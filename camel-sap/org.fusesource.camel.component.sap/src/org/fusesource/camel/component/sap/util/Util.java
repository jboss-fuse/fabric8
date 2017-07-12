package org.fusesource.camel.component.sap.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EPackage.Registry;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.XMLDefaultHandler;
import org.eclipse.emf.ecore.xmi.XMLLoad;
import org.eclipse.emf.ecore.xmi.XMLParserPool;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.XMLSave;
import org.eclipse.emf.ecore.xmi.impl.SAXXMIHandler;
import org.eclipse.emf.ecore.xmi.impl.SAXXMLHandler;
import org.eclipse.emf.ecore.xmi.impl.XMILoadImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.emf.ecore.xmi.impl.XMISaveImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLLoadImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLParserPoolImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLSaveImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLString;
import org.eclipse.emf.ecore.xml.namespace.XMLNamespacePackage;
import org.eclipse.emf.ecore.xml.type.XMLTypePackage;
import org.eclipse.emf.edit.command.SetCommand;
import org.eclipse.emf.edit.domain.AdapterFactoryEditingDomain;
import org.eclipse.emf.edit.domain.EditingDomain;
import org.fusesource.camel.component.sap.model.idoc.IdocPackage;
import org.fusesource.camel.component.sap.model.rfc.RfcPackage;
import org.xml.sax.InputSource;

import com.sap.conn.jco.JCoContext;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoRequest;

/**
 * @author punkhorn
 *
 */
public class Util {

	public static final Registry registry = EPackage.Registry.INSTANCE;
	
	private static final SimpleDateFormat sapDateFormat = new SimpleDateFormat("yyyyMMdd");

	private static final SimpleDateFormat sapTimeFormat = new SimpleDateFormat("HHmmss");

	public static synchronized Date convertSapDateStringToDate(String sapDateString) {
		try {
			return sapDateFormat.parse(sapDateString);
		} catch (ParseException e) {
			return null;
		}
	}

	public static synchronized Date convertSapTimeStringToDate(String sapTimeString) {
		try {
			return sapTimeFormat.parse(sapTimeString);
		} catch (ParseException e) {
			return null;
		}
	}

	/**
	 * Marshals the given {@link EObject} into a string.
	 * 
	 * @param eObject
	 *            - the {@link EObject} to be marshaled.
	 * @return The marshaled content of {@link EObject}.
	 * @throws IOException
	 */
	public static String marshal(EObject eObject) throws IOException {
		ensureBasePackages();
		XMLResource resource = createXMLResource();
		eObject = EcoreUtil.copy(eObject);
		resource.getContents().add(eObject);
		StringWriter out = new StringWriter();

		Map<String, Object> options = serializeOptions();

		resource.save(out, options);
		return out.toString();
	}

	/**
	 * Unmarshals the given string content into an {@link EObject} instance.
	 * 
	 * @param string
	 *            - the string content to unmarshal.
	 * @return The {@link EObject} instance unmarshaled from the string
	 *         content.
	 * @throws IOException
	 */
	public static EObject unmarshal(String string) throws IOException {
		ensureBasePackages();
		XMLResource resource = createXMLResource();
		StringReader in = new StringReader(string);

		Map<String, Object> options = unserializeOptions();

		resource.load(new InputSource(in), options);
		return resource.getContents().get(0);
	}

	/**
	 * Saves the given <code>eObject</code> to the <code>file</code>.
	 * 
	 * @param file
	 *            - the file to save <code>eObject</code> to.
	 * @param eObject
	 *            - the object to save.
	 * @throws IOException
	 */
	public static void save(File file, EObject eObject) throws IOException {
		ensureBasePackages();
		Resource res = createXMLResource(file);
		eObject = EcoreUtil.copy(eObject);
		res.getContents().add(eObject);
		Map<String, Object> options = serializeOptions();
		res.save(options);
	}

	/**
	 * Loads an {@link EObject} from the <code>file</code>.
	 * 
	 * @param file
	 *            - the file to load {@link EObject} from.
	 * @return The {@link EObject} loaded from <code>file</code>.
	 * @throws IOException
	 */
	public static EObject load(File file) throws IOException {
		ensureBasePackages();
		Resource res = createXMLResource(file);

		Map<String, Object> options = unserializeOptions();

		res.load(options);
		return res.getContents().get(0);
	}

	/**
	 * Sets the given <code>value</code> on the named feature of
	 * <code>eObject</code>.
	 * 
	 * @param eObject
	 *            - the object to set <code>value</code> on.
	 * @param featureName
	 *            - the name of the feature to set.
	 * @param value
	 *            - the value to set.
	 * @return <code>true</code> if the <code>value</code> was set;
	 *         <code>false</code> otherwise.
	 */
	public static boolean setValue(EObject eObject, String featureName, Object value) {
		EStructuralFeature feature = eObject.eClass().getEStructuralFeature(featureName);
		if (feature == null)
			return false;
		return setValue(eObject, feature, value);
	}

	/**
	 * Sets the given <code>value</code> on the <code>feature</code> of
	 * <code>eObject</code>.
	 * 
	 * @param eObject
	 *            - the object to set <code>value</code> on.
	 * @param feature
	 *            - the feature to set.
	 * @param value
	 *            - the value to set.
	 * @return <code>true</code> if the <code>value</code> was set;
	 *         <code>false</code> otherwise.
	 */
	public static boolean setValue(EObject eObject, EStructuralFeature feature, Object value) {
		try {
			EditingDomain editingDomain = AdapterFactoryEditingDomain.getEditingDomainFor(eObject);
			if (editingDomain == null) {
				eObject.eSet(feature, value);
			} else {
				Command setCommand = SetCommand.create(editingDomain, eObject, feature, value);
				editingDomain.getCommandStack().execute(setCommand);
			}
			return true;
		} catch (Throwable exception) {
			return false;
		}
	}

	/**
	 * Gets the given <code>value</code> of the <code>feature</code> from
	 * <code>eObject</code>.
	 * 
	 * @param eObject
	 *            - the object to get <code>value</code> from.
	 * @param feature
	 *            - the feature to get.
	 * @return The value of the feature.
	 */
	public static Object getValue(EObject eObject, EStructuralFeature feature) {
		try {
			Object value = eObject.eGet(feature);
			if (value == null && feature instanceof EReference) {
				EClass eClass = ((EReference) feature).getEReferenceType();
				value = eClass.getEPackage().getEFactoryInstance().create(eClass);
				setValue(eObject, feature, value);
			}
			return value;
		} catch (Throwable exception) {
			return null;
		}
	}

	/**
	 * Gets the given <code>value</code> of the named feature from
	 * <code>eObject</code>.
	 * 
	 * @param eObject
	 *            - the object to get <code>value</code> from.
	 * @param featureName
	 *            - the name of feature to get.
	 * @return The value of the feature.
	 */
	public static Object getValue(EObject object, String featureName) {
		EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
		if (feature == null)
			return null;
		return getValue(object, feature);
	}

	/**
	 * Serializes <code>eObject</code> to returned output stream.
	 * 
	 * @param eObject
	 *            - the object to serialize.
	 * @return The output stream containing serialized object.
	 * @throws IOException
	 */
	public static OutputStream toOutputStream(EObject eObject) throws IOException {
		ensureBasePackages();
		XMLResource resource = createXMLResource();
		eObject = EcoreUtil.copy(eObject);
		resource.getContents().add(eObject);
		OutputStream out = new ByteArrayOutputStream();

		Map<String, Object> options = serializeOptions();

		resource.save(out, options);
		return out;
	}

	/**
	 * Prints <code>eObject</code> to standard out.
	 * 
	 * @param eObject
	 *            - the object to print.
	 * @throws IOException
	 */
	public static void print(EObject eObject) throws IOException {
		ensureBasePackages();
		XMLResource resource = createXMLResource();
		eObject = EcoreUtil.copy(eObject);
		resource.getContents().add(eObject);
		Map<String, Object> options = serializeOptions();
		
		resource.save(System.out, options);
	}

	/**
	 * Serializes <code>eObject</code> to returned input stream.
	 * 
	 * @param eObject
	 *            - the object to serialize.
	 * @return The input stream containing serialized object.
	 * @throws IOException
	 */
	public static InputStream toInputStream(EObject eObject) throws IOException {
		String string = marshal(eObject);
		return new ByteArrayInputStream(string.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Unserializes object from <code>in</code>.
	 * 
	 * @param in
	 *            - The input steam containing serialized object.
	 * @return The unserialized object.
	 * @throws IOException
	 */
	public static EObject fromInputStream(InputStream in) throws IOException {
		ensureBasePackages();
		Resource res = createXMLResource();

		Map<String, Object> options = unserializeOptions();

		res.load(in, options);
		return res.getContents().get(0);
	}

	/**
	 * Adds detail entry to designated annotation of given model element.
	 * 
	 * @param modelElement
	 *            - the model element to be annotated.
	 * @param source
	 *            - the source URL of annotation to be added to.
	 * @param key
	 *            - the key of the detail entry to be added to annotation.
	 * @param value
	 *            - the value of the detail entry to added to annotation.
	 */
	public static void addAnnotation(EModelElement modelElement, String source, String key, String value) {
		EAnnotation annotation = modelElement.getEAnnotation(source);
		if (annotation == null) {
			annotation = EcoreFactory.eINSTANCE.createEAnnotation();
			annotation.setSource(source);
			annotation.setEModelElement(modelElement);
		}
		annotation.getDetails().put(key, value);
	}

	/**
	 * Returns the value of the annotation from <code>modelElement</code>
	 * identified by <code>source</code> and <code>key</code>.
	 * 
	 * @param modelElement
	 *            - the annotated model element.
	 * @param source
	 *            - the namespace of annotation.
	 * @param key
	 *            - the key of annotation.
	 * @return The value of annotation.
	 */
	public static String getAnnotation(EModelElement modelElement, String source, String key) {
		EAnnotation annotation = modelElement.getEAnnotation(source);
		if (annotation == null) {
			return null;
		}
		return annotation.getDetails().get(key);
	}

	/**
	 * Begin transaction on given destination.
	 * 
	 * @param jcoDestination
	 *            = the destination to start transaction on.
	 */
	public static void beginTransaction(JCoDestination jcoDestination) {
		JCoContext.begin(jcoDestination);
	}

	/**
	 * Commit transaction on given transaction.
	 * 
	 * @param jcoDestination
	 *            = the destination to commit transaction on.
	 * @throws JCoException
	 */
	public static void commitTransaction(JCoDestination jcoDestination) throws JCoException {
		try {
			JCoRequest request = jcoDestination.getRepository().getRequest("BAPI_TRANSACTION_COMMIT");
			request.setValue("WAIT", "X");
			request.execute(jcoDestination);
		} finally {
			JCoContext.end(jcoDestination);
		}
	}

	/**
	 * Rollback transaction on given transaction.
	 * 
	 * @param jcoDestination
	 *            = the destination to rollback transaction on.
	 * @throws JCoException
	 */
	public static void rollbackTransaction(JCoDestination jcoDestination) throws JCoException {
		try {
			JCoRequest request = jcoDestination.getRepository().getRequest("BAPI_TRANSACTION_ROLLBACK");
			request.execute(jcoDestination);
		} finally {
			JCoContext.end(jcoDestination);
		}
	}

	/**
	 * Save packages in Global Package Repository to given file.
	 * 
	 * @param file
	 *            - The file to save packages into. NB: this file must end with
	 *            '.ecore' extension.
	 * @throws IOException
	 */
	public static void saveRegistry(File file) throws IOException {
		ensureBasePackages();

		Resource resource = createXMIResource(file);

		Set<String> nsURIs = new HashSet<>();
		nsURIs.addAll(registry.keySet());
		for (String nsURI : nsURIs) {
			EPackage ePackage = EPackage.Registry.INSTANCE.getEPackage(nsURI);
			resource.getContents().add(ePackage);
		}

		Map<String, Object> options = serializeOptions();
		resource.save(options);
	}

	/**
	 * Loads packages stored in given file into Global Package Repository.
	 * 
	 * @param file
	 *            - The file to load packages from. NB: this file must end with
	 *            '.ecore' extension.
	 * @throws IOException
	 */
	public static void loadRegistry(File file) throws IOException {
		ensureBasePackages();
		
		XMIResource resource = createXMIResource(file);

		Map<String, Object> options = unserializeOptions();
		resource.load(options);

		ListIterator<EObject> it = resource.getContents().listIterator();
		while (it.hasNext()) {
			EObject eObj = it.next();
			if (eObj instanceof EPackage) {
				EPackage ePackage = (EPackage) eObj;
				if (!(IdocPackage.eNS_URI.endsWith(ePackage.getNsURI()) || RfcPackage.eNS_URI.endsWith(ePackage.getNsURI()))) {
					// Only add non static packages to registry.
					reattachStaticPackageSuperTypes(ePackage);
					registry.put(ePackage.getNsURI(), ePackage);
				}
			}
		}

	}

	/**
	 * Re-attaches super types of Dynamic types derived from classes defined in static packaged.
	 * This operation is necessary when loading a
	 * package from storage since its EClasses reference the stored instance of
	 * static base package classes instead of static package in runtime.
	 * 
	 * @param ePackage
	 *            - package containing classes whose super types will be
	 *            re-attached.
	 */
	public static void reattachStaticPackageSuperTypes(EPackage ePackage) {
		if (ePackage.getNsURI().startsWith(IdocPackage.eNS_URI)) {
			for (EClassifier eClassifier : ePackage.getEClassifiers()) {
				if (eClassifier instanceof EClass) {
					EClass eClass = (EClass) eClassifier;
					EList<EClass> superTypes = eClass.getESuperTypes();
					for (int i = 0; i < superTypes.size(); i++) {
						EClass superClass = superTypes.get(i);
						switch (superClass.getName()) {
						case "Document":
							superTypes.set(i, IdocPackage.eINSTANCE.getDocument());
							continue;
						case "Segment":
							superTypes.set(i, IdocPackage.eINSTANCE.getSegment());
							continue;
						case "SegmentChildren":
							superTypes.set(i, IdocPackage.eINSTANCE.getSegmentChildren());
							continue;
						case "SegmentList":
							superTypes.set(i, IdocPackage.eINSTANCE.getSegmentList());
							continue;
						}
					}
				}
			}
		} else if (ePackage.getNsURI().startsWith(RfcPackage.eNS_URI)) {
			for (EClassifier eClassifier : ePackage.getEClassifiers()) {
				if (eClassifier instanceof EClass) {
					EClass eClass = (EClass) eClassifier;
					EList<EClass> superTypes = eClass.getESuperTypes();
					for (int i = 0; i < superTypes.size(); i++) {
						EClass superClass = superTypes.get(i);
						switch (superClass.getName()) {
						case "AbapException":
							superTypes.set(i, RfcPackage.eINSTANCE.getAbapException());
							continue;
						case "Destination":
							superTypes.set(i, RfcPackage.eINSTANCE.getDestination());
							continue;
						case "DestinationData":
							superTypes.set(i, RfcPackage.eINSTANCE.getDestinationData());
							continue;
						case "DestinationDataEntry":
							superTypes.set(i, RfcPackage.eINSTANCE.getDestinationDataEntry());
							continue;
						case "DestinationDataStore":
							superTypes.set(i, RfcPackage.eINSTANCE.getDestinationDataStore());
							continue;
						case "DestinationDataStoreEntry":
							superTypes.set(i, RfcPackage.eINSTANCE.getDestinationDataStoreEntry());
							continue;
						case "FieldMetaData":
							superTypes.set(i, RfcPackage.eINSTANCE.getFieldMetaData());
							continue;
						case "FunctionTemplate":
							superTypes.set(i, RfcPackage.eINSTANCE.getFunctionTemplate());
							continue;
						case "ListFieldMetaData":
							superTypes.set(i, RfcPackage.eINSTANCE.getListFieldMetaData());
							continue;
						case "RecordMetaData":
							superTypes.set(i, RfcPackage.eINSTANCE.getRecordMetaData());
							continue;
						case "RepositoryData":
							superTypes.set(i, RfcPackage.eINSTANCE.getRepositoryData());
							continue;
						case "RepositoryDataStore":
							superTypes.set(i, RfcPackage.eINSTANCE.getRepositoryDataStore());
							continue;
						case "RepositoryDataEntry":
							superTypes.set(i, RfcPackage.eINSTANCE.getRepositoryDataEntry());
							continue;
						case "RepositoryDataStoreEntry":
							superTypes.set(i, RfcPackage.eINSTANCE.getRepositoryDataStoreEntry());
							continue;
						case "RFC":
							superTypes.set(i, RfcPackage.eINSTANCE.getRFC());
							continue;
						case "Server":
							superTypes.set(i, RfcPackage.eINSTANCE.getServer());
							continue;
						case "ServerData":
							superTypes.set(i, RfcPackage.eINSTANCE.getServerData());
							continue;
						case "ServerDataEntry":
							superTypes.set(i, RfcPackage.eINSTANCE.getServerDataEntry());
							continue;
						case "ServerDataStore":
							superTypes.set(i, RfcPackage.eINSTANCE.getServerDataStore());
							continue;
						case "Request":
							superTypes.set(i, RfcPackage.eINSTANCE.getRequest());
							continue;
						case "Response":
							superTypes.set(i, RfcPackage.eINSTANCE.getResponse());
							continue;
						case "Structure":
							superTypes.set(i, RfcPackage.eINSTANCE.getStructure());
							continue;
						case "Table":
							superTypes.set(i, RfcPackage.eINSTANCE.getTable());
							continue;
						}
					}
				}
			}
		}
	}

	public static synchronized void ensureBasePackages() {
		@SuppressWarnings("unused")
		Object tmp;
		tmp = XMLTypePackage.eINSTANCE;
		tmp = XMLNamespacePackage.eINSTANCE;
		tmp = EcorePackage.eINSTANCE;
		tmp = RfcPackage.eINSTANCE;
		tmp = IdocPackage.eINSTANCE;
	}

	public static void addNameSpaceDeclarations(EObject o, XMLString doc) {
		Set<String> prefixes = new HashSet<String>();
		
		// find all features with namespace prefixes
		for (EStructuralFeature feature: o.eClass().getEAllStructuralFeatures()) {
			if (feature.getName().contains("/")) { // feature got a namespace?
				if (o.eGet(feature) == null) {
					continue; // no value to save in XML so no need for prefix
				}

				// get namespace prefix 
				String name = feature.getName();
				String[] tmp = name.split("/");
				String prefix = tmp[1];
				prefixes.add(prefix);
			}
		}
		

		// get object's package
		EPackage ePackage = (EPackage) EcoreUtil.getRootContainer(o.eClass());

		for (String prefix: prefixes) {
			doc.addAttribute(XMLResource.XML_NS + ":" + prefix, ePackage.getNsURI());
		}
	}
	
	public static XMLResource createXMLResource(File file) {
		URI uri = URI.createFileURI(file.getAbsolutePath());
		return createXMLResource(uri);
	}
	
	public static XMLResource createXMLResource() {
		URI uri = URI.createFileURI("/"); // ensure relative reference URIs
		return createXMLResource(uri);
	}
	
	public static XMLResource createXMLResource(URI uri) {
		XMLResource xmlResource = new XMLResourceImpl(uri) {

			@Override
			protected XMLSave createXMLSave() {
				return new XMLSaveImpl(createXMLHelper()) {
					
					@Override
					protected void saveElementID(EObject o) {
						addNameSpaceDeclarations(o,doc);
						super.saveElementID(o);
					}
					
				};
			}
			
			@Override
			protected XMLLoad createXMLLoad() {
				return new XMLLoadImpl(createXMLHelper()) {
					
					@Override
					public XMLDefaultHandler createDefaultHandler() {
						return new SAXXMLHandler(resource, helper, options) {

							@Override
							protected EStructuralFeature getFeature(EObject object, String prefix, String name, boolean isElement) {
								if (prefix != null && prefix.length() > 0) {
									name = "/" + prefix + "/" + name;
									prefix = null;
								}
								return super.getFeature(object, prefix, name, isElement);
							}
							
							@Override
							protected void setValueFromId(EObject object, EReference eReference, String ids) {
								if ("parent".equals(eReference.getName())) {
							        SingleReference ref = new SingleReference
			                                   (object,
			                                    eReference,
			                                    ids,
			                                    -1,
			                                    getLineNumber(),
			                                    getColumnNumber());
									forwardSingleReferences.add(ref);
									return;
								}
								super.setValueFromId(object, eReference, ids);
							}

						};
					}
					
				};
			}
			
		};
		return xmlResource;
	}

	public static XMIResource createXMIResource(File file) {
		URI uri = URI.createFileURI(file.getAbsolutePath());
		return createXMIResource(uri);
	}
	
	public static XMIResource createXMIResource() {
		URI uri = URI.createFileURI("/"); // ensure relative reference URIs
		return createXMIResource(uri);
	}
	
	public static XMIResource createXMIResource(URI uri) {
		XMIResource xmiResource = new XMIResourceImpl(uri) {

			@Override
			protected XMLSave createXMLSave() {
				return new XMISaveImpl(createXMLHelper()) {
					
					@Override
					protected void saveElementID(EObject o) {
						addNameSpaceDeclarations(o,doc);
						super.saveElementID(o);
					}
					
				};
			}
			
			@Override
			protected XMLLoad createXMLLoad() {
				return new XMILoadImpl(createXMLHelper()) {
					
					@Override
					public XMLDefaultHandler createDefaultHandler() {
						return new SAXXMIHandler(resource, helper, options) {

							@Override
							protected EStructuralFeature getFeature(EObject object, String prefix, String name, boolean isElement) {
								if (prefix != null && prefix.length() > 0) {
									name = "/" + prefix + "/" + name;
									prefix = null;
								}
								return super.getFeature(object, prefix, name, isElement);
							}
							
							@Override
							protected void setValueFromId(EObject object, EReference eReference, String ids) {
								if ("parent".equals(eReference.getName())) {
							        SingleReference ref = new SingleReference
			                                   (object,
			                                    eReference,
			                                    ids,
			                                    -1,
			                                    getLineNumber(),
			                                    getColumnNumber());
									forwardSingleReferences.add(ref);
									return;
								}
								super.setValueFromId(object, eReference, ids);
							}

						};
					}
					
				};
			}
			
		};
		return xmiResource;
	}

	protected static Map<String, Object> serializeOptions() {
		Map<String, Object> options = new HashMap<String, Object>();
		List<Object> lookupTable = new ArrayList<Object>();
		options.put(XMIResource.OPTION_CONFIGURATION_CACHE, Boolean.TRUE);
		options.put(XMIResource.OPTION_USE_CACHED_LOOKUP_TABLE, lookupTable);
		options.put(XMIResource.OPTION_USE_ENCODED_ATTRIBUTE_STYLE, Boolean.FALSE);
		options.put(XMIResource.OPTION_EXTENDED_META_DATA, Boolean.TRUE);
		return options;
	}
	
	protected static Map<String, Object> unserializeOptions() {
		Map<String, Object> options = new HashMap<String, Object>();
		XMLParserPool parserPool = new XMLParserPoolImpl();
		Map<Object, Object> nameToFeatureMap = new HashMap<Object, Object>();
		options.put(XMIResource.OPTION_DEFER_ATTACHMENT, Boolean.TRUE);
		options.put(XMIResource.OPTION_DEFER_IDREF_RESOLUTION, Boolean.TRUE);
		options.put(XMIResource.OPTION_USE_DEPRECATED_METHODS, Boolean.TRUE);
		options.put(XMIResource.OPTION_USE_PARSER_POOL, parserPool);
		options.put(XMIResource.OPTION_USE_XML_NAME_TO_FEATURE_MAP, nameToFeatureMap);
		options.put(XMIResource.OPTION_EXTENDED_META_DATA, Boolean.TRUE);
		return options;
	}

	protected static String convertSAPNamespaceToXMLName(String name) {
		if (name != null && name.contains("/")) {
			name = name.replaceFirst("/", "").replaceFirst("/", ":");
		}
		return name;
	}

	protected static String convertXMLNameToSAPNamespace(String name) {
		if (name != null && name.contains(":")) {
			name = "/" + name.replace(":", "/");
		}
		return name;
	}

	protected static String convertSAPNamespaceToXMLPrefix(String name) {
		if (name != null && name.contains("/")) {
			name = name.replaceFirst("/", "").replaceFirst("/", "_");
		}
		return name;
	}
}
