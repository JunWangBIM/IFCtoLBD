package org.lbd.ifc2bot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.lbd.ifc2bot.rdfpath.InvRDFStep;
import org.lbd.ifc2bot.rdfpath.RDFStep;
import org.lbd.ns.BOT;
import org.lbd.ns.IfcOwl;
import org.lbd.ns.RDFS;

import be.ugent.IfcSpfReader;
import guidcompressor.GuidCompressor;

/*
* The GNU Affero General Public License
* 
* Copyright (c) 2017, 2018 Jyrki Oraskari (Jyrki.Oraskari@gmail.f)
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

public class IfcOWL2BOT {

	private Model ifcowl_model;
	private Model ontology_model = null;
	private Map<String, List<Resource>> ifcowl_product_map = new HashMap<>();
	private final String uriBase;
	private static final String props_base = "https://w3id.org/product/props/";

	// URI-propertyset
	private Map<String, PropertySet> propertysets = new HashMap<>();

	public IfcOWL2BOT(String ifc_filename, String uriBase, String target_file) {
		if (!uriBase.endsWith("#") && !uriBase.endsWith("/"))
			uriBase += "#";
		this.uriBase = uriBase;
		ontology_model = ModelFactory.createDefaultModel();
		readInOntologies();
		createIfcBOTMapping();

		Model output_model = ModelFactory.createDefaultModel();
		RDFS.addNameSpace(output_model);
		BOT.addNameSpaces(output_model);
		IfcOwl.addNameSpace(output_model);
		output_model.setNsPrefix("xmlschema", "http://www.w3.org/2001/XMLSchema#");
		output_model.setNsPrefix("rdfschema", "http://www.w3.org/2000/01/rdf-schema#");
		
		
		output_model.setNsPrefix("inst", uriBase);
		output_model.setNsPrefix("props", props_base);
					 
		ifcowl_model = readAndConvertIFC(ifc_filename, uriBase);

		listPropertysets().stream().map(rn -> rn.asResource()).forEach(propertyset -> {
			RDFStep[] path = { new RDFStep(IfcOwl.hasProperties_IfcPropertySet) };
			pathQuery(propertyset, path).forEach(propertySingleValue -> {

				RDFStep[] name_path = { new RDFStep(IfcOwl.name_IfcProperty), new RDFStep(IfcOwl.hasString) };
				final List<RDFNode> property_name = new ArrayList<>();
				pathQuery(propertySingleValue.asResource(), name_path).forEach(name -> property_name.add(name));


				final List<RDFNode> property_value = new ArrayList<>();
				RDFStep[] value_pathS = { new RDFStep(IfcOwl.nominalValue_IfcPropertySingleValue),
						new RDFStep(IfcOwl.hasString) };
				pathQuery(propertySingleValue.asResource(), value_pathS).forEach(value -> property_value.add(value));

				RDFStep[] value_pathD = { new RDFStep(IfcOwl.nominalValue_IfcPropertySingleValue),
						new RDFStep(IfcOwl.hasDouble) };
				pathQuery(propertySingleValue.asResource(), value_pathD).forEach(value -> property_value.add(value));

				RDFStep[] value_pathI = { new RDFStep(IfcOwl.nominalValue_IfcPropertySingleValue),
						new RDFStep(IfcOwl.hasInteger) };
				pathQuery(propertySingleValue.asResource(), value_pathI).forEach(value -> property_value.add(value));

				RDFStep[] value_pathB = { new RDFStep(IfcOwl.nominalValue_IfcPropertySingleValue),
						new RDFStep(IfcOwl.hasBoolean) };
				pathQuery(propertySingleValue.asResource(), value_pathB).forEach(value -> property_value.add(value));

				
				if (property_name.size() > 0 && property_value.size() > 0) {
					RDFNode pname = property_name.get(0);
					RDFNode pvalue = property_value.get(0);
					if (!pname.toString().equals(pvalue.toString())) {
						PropertySet ps = this.propertysets.get(propertyset.getURI());
						if (ps == null) {
							ps = new PropertySet();
							this.propertysets.put(propertyset.getURI(), ps);
						}
						if(pvalue.toString().trim().length()>0)
						{
						  ps.put(toCamelCase(pname.toString()), pvalue);
						}
					}
				}

			});
		});

		listSites().stream().map(rn -> rn.asResource()).forEach(site -> {
			Resource sio = createformattedURI(site, output_model, "Site");
			addAttrributes(site.asResource(), sio);

			sio.addProperty(RDF.type, BOT.site);

			listPropertysets(site).stream().map(rn -> rn.asResource()).forEach(propertyset -> {
					PropertySet p_set = this.propertysets.get(propertyset.getURI());
					if (p_set != null) {
						for (String k : p_set.getMap().keySet()) {
							Property property = output_model.createProperty(this.props_base + k);
							sio.addProperty(property, p_set.getMap().get(k));
						}
					}
			});

			listBuildings(site).stream().map(rn -> rn.asResource()).forEach(building -> {
				if(!getType(building.asResource()).get().getURI().equals("http://www.buildingsmart-tech.org/ifcOWL/IFC2X3_TC1#IfcBuilding"))
					return;
				Resource bo = createformattedURI(building, output_model, "Building");
				addAttrributes(building, bo);

				bo.addProperty(RDF.type, BOT.building);
				sio.addProperty(BOT.hasBuilding, bo);

				listPropertysets(building).stream().map(rn -> rn.asResource()).forEach(propertyset -> {
						PropertySet p_set = this.propertysets.get(propertyset.getURI());
						if (p_set != null) {
							for (String k : p_set.getMap().keySet()) {
								Property property = output_model.createProperty(this.props_base + k);
								bo.addProperty(property, p_set.getMap().get(k));
							}
						}
				});

				listStoreys(building).stream().map(rn -> rn.asResource()).forEach(storey -> {
					if(!getType(storey.asResource()).get().getURI().equals("http://www.buildingsmart-tech.org/ifcOWL/IFC2X3_TC1#IfcBuildingStorey"))
						return;

					Resource so = createformattedURI(storey, output_model, "Storey");
					addAttrributes(storey, so);

					bo.addProperty(BOT.hasStorey, so);
					so.addProperty(RDF.type, BOT.storey);

					listPropertysets(storey).stream().map(rn -> rn.asResource()).forEach(propertyset -> {
						PropertySet p_set = this.propertysets.get(propertyset.getURI());
						if (p_set != null) {
							for (String k : p_set.getMap().keySet()) {
								Property property = output_model.createProperty(this.props_base + k);
								so.addProperty(property, p_set.getMap().get(k));
	
							}
						}
					});

					listContained_StoreyElements(storey).stream().map(rn -> rn.asResource()).forEach(element -> {
						connectElement(output_model, so, element);
					});

					listStoreySpaces(storey.asResource()).stream().forEach(space -> {
						if(!getType(space.asResource()).get().getURI().equals("http://www.buildingsmart-tech.org/ifcOWL/IFC2X3_TC1#IfcSpace"))
								return;
						Resource spo = createformattedURI(space.asResource(), output_model, "Space");
						addAttrributes(space.asResource(), spo);

						so.addProperty(BOT.hasSpace, spo);
						spo.addProperty(RDF.type, BOT.space);
						listContained_SpaceElements(space.asResource()).stream().map(rn -> rn.asResource())
								.forEach(element -> {
									connectElement(output_model, spo, element);
								});

						listAdjacent_SpaceElements(space.asResource()).stream().map(rn -> rn.asResource())
								.forEach(element -> {
									connectElement(output_model, spo, BOT.adjacentElement, element);
								});

						listPropertysets(space.asResource()).stream().map(rn -> rn.asResource())
								.forEach(propertyset -> {
									PropertySet p_set = this.propertysets.get(propertyset.getURI());
									if (p_set != null) {
										for (String k : p_set.getMap().keySet()) {
											Property property = output_model.createProperty(this.props_base + k);
											spo.addProperty(property, p_set.getMap().get(k));
										}
									}
								});
					});
				});
			});
		});

		// String out_filename = ifc_filename.split("\\.")[0] + "_BOT.ttl";

		try {
			FileOutputStream fo = new FileOutputStream(new File(target_file));
			output_model.write(fo, "TTL");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		System.out.println("Conversion done. File is: " + target_file);
		
	}

	// https://stackoverflow.com/questions/17078347/convert-a-string-to-modified-camel-case-in-java-or-title-case-as-is-otherwise-ca
	public static String toCamelCase(final String init) {
		if (init == null)
			return null;

		final StringBuilder ret = new StringBuilder(init.length());

		boolean first=true;
		for (final String word : init.split(" ")) {
			if (!word.isEmpty()) {
				if(first)
				{
					ret.append(word.substring(0, 1).toLowerCase());
					first=false;
				}
				else
				   ret.append(word.substring(0, 1).toUpperCase());
				ret.append(word.substring(1).toLowerCase());
			}
		}

		return ret.toString();
	}

	private void connectElement(Model output_model, Resource bot_resource, Resource ifc_element) {
		Optional<String> predefined_type = getPredefinedData(ifc_element);
		Optional<Resource> ifcowl_type = getType(ifc_element);
		Optional<Resource> bot_type = Optional.empty();
		if (ifcowl_type.isPresent()) {
			bot_type = getBOTProductType(ifcowl_type.get().getLocalName());
		}

		if (bot_type.isPresent()) {
			Resource eo = createformattedURI(ifc_element, output_model, bot_type.get().getLocalName());
			// addLabel(ifc_element, eo);
			// addDescription(ifc_element, eo);
			if (predefined_type.isPresent()) {
				Resource product = output_model.createResource(bot_type.get().getURI() + "-" + predefined_type.get());
				eo.addProperty(RDF.type, product);
			} else
				eo.addProperty(RDF.type, bot_type.get());
			eo.addProperty(RDF.type, BOT.element);
			bot_resource.addProperty(BOT.containsElement, eo);

			listPropertysets(ifc_element).stream().map(rn -> rn.asResource()).forEach(propertyset -> {
				Resource pset = getformattedURI(propertyset, output_model, "PropertySet");
				if (output_model.containsResource(pset))
					eo.addProperty(BOT.PropertySet.hasPropertySet, pset);
			});
			addAttrributes(ifc_element, eo);

			listHosted_Elements(ifc_element).stream().map(rn -> rn.asResource()).forEach(ifc_element2 -> {
				connectElement(output_model, eo, BOT.hostsElement, ifc_element2);
			});

			listAggregated_Elements(ifc_element).stream().map(rn -> rn.asResource()).forEach(ifc_element2 -> {
				connectElement(output_model, eo, BOT.aggregates, ifc_element2);
			});
		}
	}

	private void connectElement(Model output_model, Resource bot_resource, Property bot_property, Resource element) {
		Optional<Resource> ifcowl_type = getType(element);
		Optional<Resource> bot_type = Optional.empty();
		if (ifcowl_type.isPresent()) {
			bot_type = getBOTProductType(ifcowl_type.get().getLocalName());
		}

		if (bot_type.isPresent()) {
			Resource bot_object = createformattedURI(element, output_model, bot_type.get().getLocalName());
			addLabel(element, bot_object);
			addDescription(element, bot_object);
			bot_resource.addProperty(bot_property, bot_object);
		}
	}

	private void addAttrributes(Resource r, Resource bot_r) {
		r.listProperties().forEachRemaining(s -> {
			String property_string = s.getPredicate().getLocalName();
			Resource attr = s.getObject().asResource();
			Optional<Resource> atype = getType(attr);
			if (atype.isPresent())
				if (atype.get().getURI().toString()
						.equals("http://www.buildingsmart-tech.org/ifcOWL/IFC2X3_TC1#IfcLabel")) {
					attr.listProperties(IfcOwl.hasString).forEachRemaining(attr_s -> bot_r
							.addProperty(BOT.LocalProperty.getProperty(property_string), attr_s.getObject()));
				}
		});
	}

	private Optional<Resource> getType(Resource r) {
		RDFStep[] path = { new RDFStep(RDFS.type) };
		return pathQuery(r, path).stream().map(rn -> rn.asResource()).findAny();
	}

	private Optional<String> getPredefinedData(RDFNode rn) {
		if (!rn.isResource())
			return Optional.empty();
		;
		final StringBuilder sb = new StringBuilder();
		rn.asResource().listProperties().toList().stream()
				.filter(t -> t.getPredicate().getLocalName().startsWith("predefinedType_"))
				.map(t -> t.getObject().asResource().getLocalName()).forEach(o -> sb.append(o));
		if (sb.length() == 0)
			return Optional.empty();
		return Optional.of(sb.toString());
	}

	private void addDescription(Resource ifc_r, final Resource bot_r) {
		ifc_r.listProperties(IfcOwl.description).toList()
				.forEach(x -> x.getObject().asResource().listProperties(IfcOwl.hasString)
						.forEachRemaining(y -> bot_r.addProperty(RDFS.comment, y.getObject())));
	}

	private void addLabel(Resource ifc_r, final Resource bot_r) {
		ifc_r.listProperties(IfcOwl.name).toList().forEach(x -> x.getObject().asResource()
				.listProperties(IfcOwl.hasString).forEachRemaining(y -> bot_r.addProperty(RDFS.label, y.getObject())));
	}

	private void addLongName(Resource ifc_r, final Resource bot_r) {
		ifc_r.listProperties(IfcOwl.longName).toList().forEach(x -> x.getObject().asResource()
				.listProperties(IfcOwl.hasString).forEachRemaining(y -> bot_r.addProperty(RDFS.label, y.getObject())));
	}

	private Resource createformattedURI(Resource r, Model m, String product_type) {
		String guid = getGUID(r);
		if (guid == null) {
			String localName = r.getLocalName();
			if (localName.startsWith("IfcPropertySingleValue")) {
				if (localName.lastIndexOf('_') > 0)
					localName = localName.substring(localName.lastIndexOf('_') + 1);
				Resource uri = m.createResource(this.uriBase + "propertySingleValue_" + localName);
				return uri;
			}
			if (localName.toLowerCase().startsWith("ifc"))
				localName = localName.substring(3);
			Resource uri = m.createResource(this.uriBase + product_type.toLowerCase() + "_" + localName);
			return uri;
		} else {
			Resource guid_uri = m.createResource(
					this.uriBase + product_type.toLowerCase() + "_" + GuidCompressor.uncompressGuidString(guid));
			Literal l = m.createLiteral(guid);
			guid_uri.addLiteral(IfcOwl.guid_simple, l);
			return guid_uri;
		}
	}

	private Resource getformattedURI(Resource r, Model m, String product_type) {
		String guid = getGUID(r);
		if (guid == null) {
			Resource uri = m.getResource(this.uriBase + product_type + "/" + r.getLocalName());
			return uri;
		} else {
			Resource guid_uri = m
					.getResource(this.uriBase + product_type + "/" + GuidCompressor.uncompressGuidString(guid));
			return guid_uri;
		}
	}

	private String getGUID(Resource r) {
		StmtIterator i = r.listProperties(IfcOwl.guid);
		if (i.hasNext()) {
			Statement s = i.next();
			String guid = s.getObject().asResource().getProperty(IfcOwl.hasString).getObject().asLiteral()
					.getLexicalForm();
			return guid;
		}
		return null;
	}

	public Optional<Resource> getBOTProductType(String ifcType) {
		List<Resource> ret = ifcowl_product_map.get(ifcType);
		if (ret == null) {
			return Optional.empty();
		} else if (ret.size() > 1) {
			System.out.println("many " + ifcType);
			return Optional.empty();
		} else if (ret.size() > 0)
			return Optional.of(ret.get(0));
		else
			return Optional.empty();
	}

	private List<RDFNode> listSites() {
		RDFStep[] path = { new InvRDFStep(RDFS.type) };
		return pathQuery(ifcowl_model.getResource(IfcOwl.IfcSite), path);
	}

	private List<RDFNode> listBuildings(Resource site) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		return pathQuery(site, path);
	}

	private List<RDFNode> listStoreys(Resource building) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		return pathQuery(building, path);
	}

	private List<RDFNode> listStoreySpaces(Resource storey) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		ret = pathQuery(storey, path1);
		RDFStep[] path2 = { new RDFStep(IfcOwl.getProperty("objectPlacement_IfcProduct")),
				new InvRDFStep(IfcOwl.getProperty("placementRelTo_IfcLocalPlacement")),
				new InvRDFStep(IfcOwl.getProperty("objectPlacement_IfcProduct")) };
		ret.addAll(pathQuery(storey, path2));

		return ret;
	}

	private List<RDFNode> listContained_StoreyElements(Resource storey) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.getProperty("relatingStructure_IfcRelContainedInSpatialStructure")),
				new RDFStep(IfcOwl.getProperty("relatedElements_IfcRelContainedInSpatialStructure")) };
		ret = pathQuery(storey, path1);
		RDFStep[] path2 = { new RDFStep(IfcOwl.getProperty(" objectPlacement_IfcProduct")),
				new InvRDFStep(IfcOwl.getProperty("placementRelTo_IfcLocalPlacement")),
				new InvRDFStep(IfcOwl.getProperty("objectPlacement_IfcProduct")) };
		ret.addAll(pathQuery(storey, path2));
		return ret;
	}

	private List<RDFNode> listContained_SpaceElements(Resource storey) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.getProperty("relatingStructure_IfcRelContainedInSpatialStructure")),
				new RDFStep(IfcOwl.getProperty("relatedElements_IfcRelContainedInSpatialStructure")) };
		ret = pathQuery(storey, path1);
		return ret;
	}

	private List<RDFNode> listAdjacent_SpaceElements(Resource storey) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.getProperty("relatingSpace_IfcRelSpaceBoundary")),
				new RDFStep(IfcOwl.getProperty("relatedBuildingElement_IfcRelSpaceBoundary")) };
		ret = pathQuery(storey, path1);
		return ret;
	}

	private List<RDFNode> listHosted_Elements(Resource element) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.getProperty("relatingBuildingElement_IfcRelVoidsElement")),
				new RDFStep(IfcOwl.getProperty("relatedOpeningElement_IfcRelVoidsElement")),
				new InvRDFStep(IfcOwl.getProperty("relatingOpeningElement_IfcRelFillsElement")),
				new RDFStep(IfcOwl.getProperty("relatedBuildingElement_IfcRelFillsElement")) };
		ret = pathQuery(element, path1);
		RDFStep[] path2 = { new InvRDFStep(IfcOwl.getProperty("relatingBuildingElement_IfcRelVoidsElement")),
				new RDFStep(IfcOwl.getProperty("relatedOpeningElement_IfcRelVoidsElement")),
				new RDFStep(IfcOwl.getProperty("objectPlacement_IfcProduct")),
				new InvRDFStep(IfcOwl.getProperty("placementRelTo_IfcLocalPlacement")),
				new InvRDFStep(IfcOwl.getProperty("objectPlacement_IfcProduct")) };
		ret.addAll(pathQuery(element, path2));

		return ret;
	}

	private List<RDFNode> listAggregated_Elements(Resource element) {
		List<RDFNode> ret;

		RDFStep[] path1 = { new InvRDFStep(IfcOwl.getProperty("relatedObjects_IfcRelDecomposes")),
				new RDFStep(IfcOwl.getProperty("relatingObject_IfcRelDecomposes")) };
		ret = pathQuery(element, path1);
		return ret;
	}

	private List<Resource> listElements() {
		final List<Resource> ret = new ArrayList<>();
		ifcowl_model.listStatements().filterKeep(t1 -> t1.getPredicate().equals(RDFS.type)).filterKeep(t2 -> {
			Optional<Resource> product_type = getBOTProductType(t2.getObject().asResource().getLocalName());
			return product_type.isPresent();
		}).mapWith(t1 -> t1.getSubject()).forEachRemaining(s -> ret.add(s));
		;
		return ret;
	}

	private List<RDFNode> listPropertysets(Resource resource) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatedObjects_IfcRelDefines),
				new RDFStep(IfcOwl.relatingPropertyDefinition_IfcRelDefinesByProperties) };
		return pathQuery(resource, path);
	}

	private List<RDFNode> listPropertysets() {
		RDFStep[] path = { new InvRDFStep(RDFS.type) };
		return pathQuery(ifcowl_model.getResource(IfcOwl.IfcPropertySet), path);
	}

	private List<RDFNode> pathQuery(Resource r, RDFStep[] path) {
		List<RDFStep> path_list = Arrays.asList(path);
		if (r.getModel() == null)
			return new ArrayList<RDFNode>();
		Optional<RDFStep> step = path_list.stream().findFirst();
		if (step.isPresent()) {
			List<RDFNode> step_result = step.get().next(r);
			if (path.length > 1) {
				final List<RDFNode> result = new ArrayList<RDFNode>();
				step_result.stream().filter(rn1 -> rn1.isResource()).map(rn2 -> rn2.asResource()).forEach(r1 -> {
					List<RDFStep> tail = path_list.stream().skip(1).collect(Collectors.toList());
					result.addAll(pathQuery(r1, tail.toArray(new RDFStep[tail.size()])));
				});
				return result;
			} else
				return step_result;
		}
		return new ArrayList<RDFNode>();
	}

	public void createIfcBOTMapping() {
		StmtIterator si = ontology_model.listStatements();
		while (si.hasNext()) {
			Statement product_BE_ontology_statement = si.next();
			if (product_BE_ontology_statement.getPredicate().toString().toLowerCase().contains("seealso")) {
				if (product_BE_ontology_statement.getObject().isLiteral())
					continue;
				if (!product_BE_ontology_statement.getObject().isResource())
					continue;
				Resource ifcowl_class = product_BE_ontology_statement.getObject().asResource();
				// This adds the seeAlso mapping directly
				List<Resource> resource_list = ifcowl_product_map.getOrDefault(ifcowl_class.getLocalName(),
						new ArrayList<Resource>());
				ifcowl_product_map.put(ifcowl_class.getLocalName(), resource_list);
				resource_list.add(product_BE_ontology_statement.getSubject());

				// This adds the nearest subclasses of the IFC entity class

				StmtIterator subclass_iterator = ifcowl_class.listProperties(RDFS.subClassOf);
				while (subclass_iterator.hasNext()) {
					Statement su = subclass_iterator.next();
					Resource ifcowl_subclass = su.getObject().asResource();
					List<Resource> r_list = ifcowl_product_map.getOrDefault(ifcowl_subclass.getLocalName(),
							new ArrayList<Resource>());
					ifcowl_product_map.put(ifcowl_subclass.getLocalName(), r_list);
					r_list.add(product_BE_ontology_statement.getSubject());
				}

			}
		}
	}

	public Model readAndConvertIFC(String ifc_file, String uriBase) {
		try {
			IfcSpfReader rj = new IfcSpfReader();
			try {
				Model m = ModelFactory.createDefaultModel();
				ByteArrayOutputStream stringStream = new ByteArrayOutputStream();
				rj.convert(ifc_file, stringStream, uriBase);
				InputStream stream = new ByteArrayInputStream(
						stringStream.toString().getBytes(StandardCharsets.UTF_8.name()));
				m.read(stream, null, "TTL");
				return m;
			} catch (IOException e) {
				e.printStackTrace();
			}

		} catch (Exception e) {
			e.printStackTrace();

		}
		System.out.println("IFC-RDF conversion not done");
		return ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
	}

	private void readInOntologies() {
		readInOntologyTTL("prod.ttl");
		readInOntologyTTL("prod_building_elements.ttl");
		readInOntologyTTL("prod_mep.ttl");
		readInOntologyTTL("prod_mep.ttl");
		readInOntologyTTL("IFC2X3_Final.ttl");
	}

	private void readInOntologyTTL(String ontology_file) {

		InputStream in = null;
		try {
			in = IfcOWL2BOT.class.getResourceAsStream(ontology_file);
			if (in == null) {
				try {
					in = new FileInputStream(new File("c:/jo/products/" + ontology_file));
				} catch (Exception e) {
					e.printStackTrace();
					return;
				}
			}
			ontology_model.read(in, null, "TTL");
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		if (args.length > 2) {
			new IfcOWL2BOT(args[0], args[1], args[2]);
		} else
			System.out.println("Usage: IfcOWL2BOT1 ifc_filename base_uri targer_file");
	}

}
