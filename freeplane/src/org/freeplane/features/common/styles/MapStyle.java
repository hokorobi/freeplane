/*
 *  Freeplane - mind map editor
 *  Copyright (C) 2009 Dimitry Polivaev
 *
 *  This file author is Dimitry Polivaev
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.freeplane.features.common.styles;

import java.awt.Color;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Vector;

import org.freeplane.core.addins.NodeHookDescriptor;
import org.freeplane.core.addins.PersistentNodeHook;
import org.freeplane.core.controller.Controller;
import org.freeplane.core.controller.IMapLifeCycleListener;
import org.freeplane.core.extension.IExtension;
import org.freeplane.core.io.IElementContentHandler;
import org.freeplane.core.io.IElementDOMHandler;
import org.freeplane.core.io.IElementHandler;
import org.freeplane.core.io.IExtensionElementWriter;
import org.freeplane.core.io.ITreeWriter;
import org.freeplane.core.resources.NamedObject;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.undo.IActor;
import org.freeplane.core.undo.IUndoHandler;
import org.freeplane.core.util.ColorUtils;
import org.freeplane.features.common.filter.FilterController;
import org.freeplane.features.common.filter.condition.ConditionFactory;
import org.freeplane.features.common.filter.condition.ASelectableCondition;
import org.freeplane.features.common.map.MapChangeEvent;
import org.freeplane.features.common.map.MapController;
import org.freeplane.features.common.map.MapModel;
import org.freeplane.features.common.map.MapWriter;
import org.freeplane.features.common.map.ModeController;
import org.freeplane.features.common.map.NodeModel;
import org.freeplane.features.common.map.MapWriter.Mode;
import org.freeplane.features.common.styles.ConditionalStyleModel.Item;
import org.freeplane.features.common.url.UrlManager;
import org.freeplane.n3.nanoxml.XMLElement;

/**
 * @author Dimitry Polivaev
 * Mar 9, 2009
 */
@NodeHookDescriptor(hookName = "MapStyle")
public class MapStyle extends PersistentNodeHook implements IExtension, IMapLifeCycleListener {
	public static final String RESOURCES_BACKGROUND_COLOR = "standardbackgroundcolor";
	public static final String MAP_STYLES = "MAP_STYLES";

	public enum WriterHint {
		FORCE_FORMATTING
	};

	public static final String MAX_NODE_WIDTH = "max_node_width";
	
	public MapStyle( final boolean persistent) {
		super();
		ModeController modeController = Controller.getCurrentModeController();
		if (persistent) {
			final MapController mapController = modeController.getMapController();
			mapController.getWriteManager().addExtensionElementWriter(getExtensionClass(),
			    new XmlWriter());
			mapController.getReadManager().addElementHandler("conditional_styles", new IElementDOMHandler() {
				public Object createElement(Object parent, String tag, XMLElement attributes) {
					return parent;
				}
				
				public void endElement(Object parent, String tag, Object element, XMLElement dom) {
					final NodeModel node = (NodeModel) parent;
					final MapStyleModel mapStyleModel = MapStyleModel.getExtension(node);
					loadConditionalStyles(mapStyleModel.getConditionalStyleModel(), dom);
				}
			});
			
			mapController.getReadManager().addElementHandler("map_styles",  new IElementContentHandler() {
				public Object createElement(Object parent, String tag, XMLElement attributes) {
	                return parent;
                }
				public void endElement(final Object parent, final String tag, final Object userObject,
				                       final XMLElement attributes, final String content) {
					final NodeModel node = (NodeModel) userObject;
					final MapStyleModel mapStyleModel = MapStyleModel.getExtension(node);
					if (mapStyleModel == null) {
						return;
					}
					final MapModel map = node.getMap();
					mapStyleModel.createStyleMap(map, mapStyleModel, content);
					map.getIconRegistry().addIcons(mapStyleModel.getStyleMap());
				}

			}
			);

		}
		modeController.getMapController().addMapLifeCycleListener(this);
		if (modeController.getModeName().equals("MindMap")) {
			modeController.addAction(new MapBackgroundColorAction(this));
			modeController.addAction(new CopyMapStylesAction());
		}
		final MapController mapController = modeController.getMapController();
		mapController.addMapLifeCycleListener(this);
		modeController.addAction(new MaxNodeWidthAction());
	}

	protected class XmlWriter implements IExtensionElementWriter {
		public void writeContent(final ITreeWriter writer, final Object object, final IExtension extension)
		        throws IOException {
			final MapStyleModel mapStyleModel = (MapStyleModel) extension;
			final MapModel styleMap = mapStyleModel.getStyleMap();
			if (styleMap == null) {
				return;
			}
			final MapWriter mapWriter = Controller.getCurrentModeController().getMapController().getMapWriter();
			final StringWriter sw = new StringWriter();
			sw.append("<map_styles>\n");
			final NodeModel rootNode = styleMap.getRootNode();
			try {
				mapWriter.writeNodeAsXml(sw, rootNode, Mode.STYLE, true, true);
			}
			catch (final IOException e) {
				e.printStackTrace();
			}
			sw.append("</map_styles>\n");
			final XMLElement element = new XMLElement("hook");
			saveExtension(extension, element);
			writer.addElement(sw.toString(), element);
		}
	}

	@Override
	protected XmlWriter createXmlWriter() {
		return null;
	}

	protected class MyXmlReader extends XmlReader{
		public Object createElement(final Object parent, final String tag, final XMLElement attributes) {
			if (null == super.createElement(parent, tag, attributes)){
				return null;
			}
			super.endElement(parent, tag, parent, attributes);
			return parent;
		}

		@Override
        public void endElement(Object parent, String tag, Object userObject, XMLElement xml) {
			NodeModel node = (NodeModel) userObject;
			loadMapStyleProperties(MapStyleModel.getExtension(node), xml);
       }

		private void loadMapStyleProperties(MapStyleModel model, XMLElement xml) {
			final Vector<XMLElement> propertyXml = xml.getChildrenNamed("properties");
			if(propertyXml.size() >= 1){
				final Map<String, String> properties = model.getProperties();
				final Properties attributes = propertyXml.get(0).getAttributes();
				for(Entry<Object, Object> attribute:attributes.entrySet()){
					properties.put(attribute.getKey().toString(), attribute.getValue().toString());
				}
			}
        }
		
	}

	@Override
	protected IElementHandler createXmlReader() {
		return new MyXmlReader();
	}

	@Override
	protected IExtension createExtension(final NodeModel node, final XMLElement element) {
		final MapStyleModel model = new MapStyleModel();
		final String colorString = element.getAttribute("background", null);
		final Color bgColor;
		if (colorString != null) {
			bgColor = ColorUtils.stringToColor(colorString);
		}
		else {
			bgColor = null;
		}
		model.setBackgroundColor(bgColor);
		final String zoomString = element.getAttribute("zoom", null);
		if (zoomString != null) {
			final float zoom = Float.valueOf(zoomString);
			model.setZoom(zoom);
		}
		final String layoutString = element.getAttribute("layout", null);
		try {
			if (layoutString != null) {
				final MapViewLayout layout = MapViewLayout.valueOf(layoutString);
				model.setMapViewLayout(layout);
			}
		}
		catch (final Exception e) {
		}
		final String maxNodeWidthString = element.getAttribute("max_node_width", null);
		try {
			if (maxNodeWidthString != null) {
				final int maxNodeWidth = Integer.valueOf(maxNodeWidthString);
				model.setMaxNodeWidth(maxNodeWidth);
			}
		}
		catch (final Exception e) {
		}
		return model;
	}

	private void loadConditionalStyles(ConditionalStyleModel conditionalStyleModel, XMLElement conditionalStylesRoot) {
		final ConditionFactory conditionFactory = FilterController.getCurrentFilterController().getConditionFactory();
		final Vector<XMLElement> styleElements = conditionalStylesRoot.getChildrenNamed("conditional_style");
		for(XMLElement styleElement : styleElements){
			final boolean isActive = Boolean.valueOf(styleElement.getAttribute("ACTIVE", "false"));
			final boolean isLast = Boolean.valueOf(styleElement.getAttribute("LAST", "false"));
			String styleText = styleElement.getAttribute("LOCALIZED_STYLE_REF", null);
			final IStyle style;
			if(styleText != null){
				style = StyleFactory.create(NamedObject.formatText((String) styleText));
			}
			else {
				style = StyleFactory.create(styleElement.getAttribute("STYLE_REF", null));
			}
			final XMLElement conditionElement = styleElement.getChildAtIndex(0);
			final ASelectableCondition condition = conditionFactory.loadCondition(conditionElement);
			if(condition != null){
				conditionalStyleModel.addCondition(isActive, condition, style, isLast);
			}
		}
    }
	private void saveConditionalStyles(ConditionalStyleModel conditionalStyleModel, XMLElement parent) {
		final int styleCount = conditionalStyleModel.getStyleCount();
		if(styleCount == 0){
			return;
		}
		final XMLElement conditionalStylesRoot = parent.createElement("conditional_styles");
		parent.addChild(conditionalStylesRoot);
		for(final Item item : conditionalStyleModel){
			final XMLElement itemElement = conditionalStylesRoot.createElement("conditional_style");
			conditionalStylesRoot.addChild(itemElement);
			itemElement.setAttribute("ACTIVE", Boolean.toString(item.isActive()));
			final Object style = item.getStyle();
			if (style instanceof StyleNamedObject) {
				final String referencedStyle = ((StyleNamedObject)style).getObject().toString();
				itemElement.setAttribute("LOCALIZED_STYLE_REF", referencedStyle);
			}
			else {
				final String referencedStyle = style.toString();
				itemElement.setAttribute("STYLE_REF", referencedStyle);
			}
			itemElement.setAttribute("LAST", Boolean.toString(item.isLast()));
			item.getCondition().toXml(itemElement);
		}
	    
    }

	public Color getBackground(final MapModel map) {
		final IExtension extension = map.getRootNode().getExtension(MapStyleModel.class);
		final Color backgroundColor = extension != null ? ((MapStyleModel) extension).getBackgroundColor() : null;
		if (backgroundColor != null) {
			return backgroundColor;
		}
		final String stdcolor = ResourceController.getResourceController().getProperty(
		    MapStyle.RESOURCES_BACKGROUND_COLOR);
		final Color standardMapBackgroundColor = ColorUtils.stringToColor(stdcolor);
		return standardMapBackgroundColor;
	}

	@Override
	protected Class<MapStyleModel> getExtensionClass() {
		return MapStyleModel.class;
	}

	public void onCreate(final MapModel map) {
		final NodeModel rootNode = map.getRootNode();
		final MapStyleModel mapStyleModel = MapStyleModel.getExtension(rootNode);
		if (mapStyleModel != null && mapStyleModel.getStyleMap() != null) {
			return;
		}
		createDefaultStyleMap(map);
	}

	private void createDefaultStyleMap(final MapModel map) {
	    final MapModel styleMapContainer = new MapModel(null);
		UrlManager.getController().loadDefault(styleMapContainer);
		moveStyle(styleMapContainer, map, false);
    }

	private void moveStyle(final MapModel sourceMap, final MapModel targetMap, boolean overwrite) {
	    final MapStyleModel source = (MapStyleModel) sourceMap.getRootNode().removeExtension(MapStyleModel.class);
		final IExtension undoHandler = targetMap.getExtension(IUndoHandler.class);
		source.getStyleMap().putExtension(IUndoHandler.class, undoHandler);
		final NodeModel targetRoot = targetMap.getRootNode();
		final MapStyleModel target = MapStyleModel.getExtension(targetRoot);
		if(target == null){
			targetRoot.addExtension(source);
		}
		else{
			target.copyFrom(source, overwrite);
		}
    }

	public void copyStyle(final URL source, final MapModel targetMap, boolean undoable) {
	    final MapModel styleMapContainer = Controller.getCurrentModeController().getMapController().newModel(null);
		final IExtension oldStyleModel = targetMap.getRootNode().removeExtension(MapStyleModel.class);
		UrlManager.getController().loadImpl(source, styleMapContainer);
		onCreate(styleMapContainer);
		moveStyle(styleMapContainer, targetMap, true);
		LogicalStyleController.getController().refreshMap(targetMap);
		if(! undoable){
			return;
		}
		final IExtension newStyleModel = targetMap.getRootNode().getExtension(MapStyleModel.class);
		IActor actor = new IActor() {
			public void undo() {
				targetMap.getRootNode().putExtension(oldStyleModel);
			}
			
			public String getDescription() {
				return "moveStyle";
			}
			
			public void act() {
				targetMap.getRootNode().putExtension(newStyleModel);
			}
		};
		Controller.getCurrentModeController().execute(actor, targetMap);
	}
	
	public void onRemove(final MapModel map) {
	}

	@Override
	protected void saveExtension(final IExtension extension, final XMLElement element) {
		final MapStyleModel mapStyleModel = (MapStyleModel) extension;
		super.saveExtension(extension, element);
		final Color backgroundColor = mapStyleModel.getBackgroundColor();
		if (backgroundColor != null) {
			element.setAttribute("background", ColorUtils.colorToString(backgroundColor));
		}
		final float zoom = mapStyleModel.getZoom();
		if (zoom != 1f) {
			element.setAttribute("zoom", Float.toString(zoom));
		}
		final MapViewLayout layout = mapStyleModel.getMapViewLayout();
		if (!layout.equals(MapViewLayout.MAP)) {
			element.setAttribute("layout", layout.toString());
		}
		element.setAttribute("max_node_width", Integer.toString(mapStyleModel.getMaxNodeWidth()));
		saveConditionalStyles(mapStyleModel.getConditionalStyleModel(), element);
		saveProperties(mapStyleModel.getProperties(), element);
	}
	
	private void saveProperties(Map<String, String> properties, XMLElement element) {
		if(properties.isEmpty()){
			return;
		}
	    final XMLElement xmlElement = new XMLElement("properties");
	    for (Entry<String, String>  entry: properties.entrySet()){
	    	xmlElement.setAttribute(entry.getKey(), entry.getValue());
	    }
	    element.addChild(xmlElement);
    }

	public void setZoom(final MapModel map, final float zoom) {
		final MapStyleModel mapStyleModel = MapStyleModel.getExtension(map);
		if (zoom == mapStyleModel.getZoom()) {
			return;
		}
		mapStyleModel.setZoom(zoom);
		Controller.getCurrentModeController().getMapController().setSaved(map, false);
	}

	public void setMaxNodeWidth(final MapModel map, final int width) {
		final MapStyleModel mapStyleModel = MapStyleModel.getExtension(map);
		final int oldMaxNodeWidth = mapStyleModel.getMaxNodeWidth();
		if (width == oldMaxNodeWidth) {
			return;
		}
		mapStyleModel.setMaxNodeWidth(width);
		Controller.getCurrentModeController().getMapController()
		    .fireMapChanged(
		        new MapChangeEvent(MapStyle.this, Controller.getCurrentController().getMap(), MapStyle.MAX_NODE_WIDTH, oldMaxNodeWidth,
		            width));
	}

	public void setMapViewLayout(final MapModel map, final MapViewLayout layout) {
		final MapStyleModel mapStyleModel = MapStyleModel.getExtension(map);
		if (layout.equals(mapStyleModel.getMapViewLayout())) {
			return;
		}
		mapStyleModel.setMapViewLayout(layout);
		Controller.getCurrentModeController().getMapController().setSaved(map, false);
	}

	public void setBackgroundColor(final MapStyleModel model, final Color actionColor) {
		final Color oldColor = model.getBackgroundColor();
		if (actionColor == oldColor || actionColor != null && actionColor.equals(oldColor)) {
			return;
		}
		final IActor actor = new IActor() {
			public void act() {
				model.setBackgroundColor(actionColor);
				Controller.getCurrentModeController().getMapController().fireMapChanged(
				    new MapChangeEvent(MapStyle.this, Controller.getCurrentController().getMap(), MapStyle.RESOURCES_BACKGROUND_COLOR,
				        oldColor, actionColor));
			}

			public String getDescription() {
				return "MapStyle.setBackgroundColor";
			}

			public void undo() {
				model.setBackgroundColor(oldColor);
				Controller.getCurrentModeController().getMapController().fireMapChanged(
				    new MapChangeEvent(MapStyle.this, Controller.getCurrentController().getMap(), MapStyle.RESOURCES_BACKGROUND_COLOR,
				        actionColor, oldColor));
			}
		};
		Controller.getCurrentModeController().execute(actor, Controller.getCurrentController().getMap());
	}
}
