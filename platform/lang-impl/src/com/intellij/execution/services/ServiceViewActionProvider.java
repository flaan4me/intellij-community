// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.services;

import com.intellij.execution.services.ServiceModel.ServiceViewItem;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.TreeExpander;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionToolbarSpacer;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ClientProperty;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.tree.TreeModelAdapter;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import static com.intellij.execution.services.ServiceViewDragHelper.getTheOnlyRootContributor;

final class ServiceViewActionProvider {
  @NonNls private static final String SERVICE_VIEW_ITEM_TOOLBAR = "ServiceViewItemToolbar";
  @NonNls static final String SERVICE_VIEW_ITEM_POPUP = "ServiceViewItemPopup";
  @NonNls private static final String SERVICE_VIEW_TREE_TOOLBAR = "ServiceViewTreeToolbar";

  private static final ServiceViewActionProvider ourInstance = new ServiceViewActionProvider();

  static ServiceViewActionProvider getInstance() {
    return ourInstance;
  }

  ActionToolbar createServiceToolbar(@NotNull JComponent component, boolean horizontal) {
    ActionGroup actions = (ActionGroup)ActionManager.getInstance().getAction(SERVICE_VIEW_ITEM_TOOLBAR);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.SERVICES_TOOLBAR, actions, horizontal);
    toolbar.setTargetComponent(component);
    return toolbar;
  }

  JComponent wrapServiceToolbar(@NotNull JComponent toolbarComponent, boolean horizontal) {
    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.add(toolbarComponent, BorderLayout.CENTER);
    toolbarComponent.addComponentListener(new ComponentListener() {
      @Override
      public void componentResized(ComponentEvent e) {
      }

      @Override
      public void componentMoved(ComponentEvent e) {
      }

      @Override
      public void componentShown(ComponentEvent e) {
        wrapper.setVisible(true);
      }

      @Override
      public void componentHidden(ComponentEvent e) {
        wrapper.setVisible(false);
      }
    });
    wrapper.add(new ActionToolbarSpacer(horizontal), horizontal ? BorderLayout.EAST : BorderLayout.SOUTH);
    return wrapper;
  }

  void installPopupHandler(@NotNull JComponent component) {
    PopupHandler.installPopupMenu(component, SERVICE_VIEW_ITEM_POPUP, ActionPlaces.SERVICES_POPUP);
  }

  ActionToolbar createMasterComponentToolbar(@NotNull JComponent component) {
    DefaultActionGroup group = new DefaultActionGroup();

    if (component instanceof JTree) {
      TreeExpander treeExpander = new ServiceViewTreeExpander((JTree)component);
      AnAction expandAllAction = CommonActionsManager.getInstance().createExpandAllAction(treeExpander, component);
      group.add(expandAllAction);
      AnAction collapseAllAction = CommonActionsManager.getInstance().createCollapseAllAction(treeExpander, component);
      group.add(collapseAllAction);
      group.addSeparator();
    }

    group.addSeparator();
    AnAction treeActions = ActionManager.getInstance().getAction(SERVICE_VIEW_TREE_TOOLBAR);
    treeActions.registerCustomShortcutSet(component, null);
    group.add(treeActions);

    ActionToolbar treeActionsToolBar = ActionManager.getInstance().createActionToolbar(ActionPlaces.SERVICES_TOOLBAR, group, true);
    treeActionsToolBar.setTargetComponent(component);

    return treeActionsToolBar;
  }

  List<AnAction> getAdditionalGearActions() {
    List<AnAction> result = new ArrayList<>();
    AnAction selectActiveServiceActions = ActionManager.getInstance().getAction("ServiceView.SelectActiveService");
    ContainerUtil.addIfNotNull(result, selectActiveServiceActions);
    result.add(Separator.getInstance());
    AnAction configureServicesActions = ActionManager.getInstance().getAction("ServiceView.ConfigureServices");
    ContainerUtil.addIfNotNull(result, configureServicesActions);
    AnAction showServicesActions = ActionManager.getInstance().getAction("ServiceView.ShowServices");
    ContainerUtil.addIfNotNull(result, showServicesActions);
    return result;
  }

  @Nullable
  static ServiceView getSelectedView(@NotNull AnActionEvent e) {
    return getSelectedView(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT));
  }

  @Nullable
  static ServiceView getSelectedView(@NotNull DataProvider provider) {
    return getSelectedView(ObjectUtils.tryCast(provider.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT.getName()), Component.class));
  }

  static boolean isActionToolBarRequired(JComponent component) {
    Boolean holder = ClientProperty.get(component, ServiceViewDescriptor.ACTION_HOLDER_KEY);
    if (Boolean.TRUE == holder) {
      return false;
    }
    while (true) {
      if (component instanceof JBTabs || component instanceof JTabbedPane) {
        return false;
      }
      if (component.getComponentCount() > 1) {
        // JBTabs is placed next to some component.
        return ContainerUtil.filterIsInstance(component.getComponents(), JBTabs.class).size() != 1;
      }
      if (component.getComponentCount() != 1) {
        return true;
      }
      Component child = component.getComponent(0);
      if (child instanceof JComponent childComponent) {
        component = childComponent;
      }
      else {
        return true;
      }
    }
  }

  @Nullable
  private static ServiceView getSelectedView(@Nullable Component contextComponent) {
    while (contextComponent != null && !(contextComponent instanceof ServiceView)) {
      contextComponent = contextComponent.getParent();
    }
    return (ServiceView)contextComponent;
  }

  private static final class ServiceViewTreeExpander extends DefaultTreeExpander {
    private boolean myFlat;

    ServiceViewTreeExpander(JTree tree) {
      super(tree);
      TreeModelListener listener = new TreeModelAdapter() {
        @Override
        protected void process(@NotNull TreeModelEvent event, @NotNull EventType type) {
          myFlat = isFlat(tree.getModel());
        }
      };
      tree.getModel().addTreeModelListener(listener);
      PropertyChangeListener propertyChangeListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent event) {
          Object oldValue = event.getOldValue();
          if (oldValue instanceof TreeModel) {
            ((TreeModel)oldValue).removeTreeModelListener(listener);
          }
          Object newValue = event.getNewValue();
          if (newValue instanceof TreeModel) {
            ((TreeModel)newValue).addTreeModelListener(listener);
          }
        }
      };
      tree.addPropertyChangeListener(JTree.TREE_MODEL_PROPERTY, propertyChangeListener);
    }

    @Override
    public boolean canExpand() {
      return super.canExpand() && !myFlat;
    }

    @Override
    public boolean canCollapse() {
      return super.canCollapse() && !myFlat;
    }

    private static boolean isFlat(TreeModel treeModel) {
      Object root = treeModel.getRoot();
      if (root == null) return false;

      int childCount = treeModel.getChildCount(root);
      for (int i = 0; i < childCount; i++) {
        Object child = treeModel.getChild(root, i);
        if (!treeModel.isLeaf(child)) {
          return false;
        }
      }
      return true;
    }
  }

  private static AnAction @NotNull [] doGetActions(@Nullable AnActionEvent e, boolean toolbar) {
    if (e == null) return AnAction.EMPTY_ARRAY;

    Project project = e.getProject();
    if (project == null) return AnAction.EMPTY_ARRAY;

    ServiceView serviceView = getSelectedView(e);
    if (serviceView == null) return AnAction.EMPTY_ARRAY;

    List<ServiceViewItem> selectedItems = serviceView.getSelectedItems();
    if (selectedItems.isEmpty()) return AnAction.EMPTY_ARRAY;

    ServiceViewDescriptor descriptor;
    if (selectedItems.size() == 1) {
      descriptor = selectedItems.get(0).getViewDescriptor();
    }
    else {
      ServiceViewContributor<?> contributor = getTheOnlyRootContributor(selectedItems);
      descriptor = contributor == null ? null : contributor.getViewDescriptor(project);
    }
    if (descriptor == null) return AnAction.EMPTY_ARRAY;

    ActionGroup group = toolbar ? descriptor.getToolbarActions() : descriptor.getPopupActions();
    return group == null ? AnAction.EMPTY_ARRAY : group.getChildren(e);
  }

  public static final class ItemToolbarActionGroup extends ActionGroup {
    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
      return doGetActions(e, true);
    }
  }

  public static final class ItemPopupActionGroup extends ActionGroup {
    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
      return doGetActions(e, false);
    }
  }
}