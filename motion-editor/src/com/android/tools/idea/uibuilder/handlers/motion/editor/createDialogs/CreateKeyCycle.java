/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.uibuilder.handlers.motion.editor.createDialogs;

import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.MEIcons;
import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.MEUI;
import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.MTag;
import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.MotionSceneAttrs;
import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.Track;
import com.android.tools.idea.uibuilder.handlers.motion.editor.ui.MeModel;
import com.android.tools.idea.uibuilder.handlers.motion.editor.ui.MotionEditorSelector;
import com.android.tools.idea.uibuilder.handlers.motion.editor.utils.Debug;
import java.awt.GridBagConstraints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSeparator;
import javax.swing.JTextField;

/**
 * This is the dialog that pops up when you create a Key Cycle
 */
public class CreateKeyCycle extends BaseCreateKey {
  String mTitle = "CREATE KEY CYCLE";
  protected String KEY_TAG = MotionSceneAttrs.Tags.KEY_CYCLE;
  protected MTag mKeyFrameSet;
  protected JTextField mPosition;
  private MTag mSelectedTransition;
  private final String POS_PROMPT = "0-100";
  private JTextField mWavePeriod;

  static String TITLE = "Create KeyCycle";
  String[] attributeOptions = MotionSceneAttrs.KeyAttributeOptions;
  String[] optionsNameSpace = MotionSceneAttrs.KeyAttributeOptionsNameSpace;
  HashMap<String,MTag> myCustomTypes = new HashMap<>();
  JComboBox<String> attribute = MEUI.makeComboBox(attributeOptions);
  String[] waveShapeOptions = {
    "sin",
    "square",
    "triangle",
    "sawtooth",
    "reverseSawtooth",
    "cos",
    "bounce"
  };
  JComboBox<String> waveShape = MEUI.makeComboBox(waveShapeOptions);

  public CreateKeyCycle(String title) {
    mTitle = title;
    setup();
  }

  public CreateKeyCycle() {
    setup();
  }

  private void setup() {
    icon = MEIcons.CREATE_TRANSITION;

    GridBagConstraints gbc = new GridBagConstraints();
    int y = createTop(gbc, mTitle);

    grid(gbc, 0, y++, 2, 1);
    gbc.weighty = 0;
    gbc.insets = MEUI.dialogSeparatorInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(new JSeparator(), gbc);

    grid(gbc, 0, y++, 2, 1);
    gbc.weighty = 0;
    gbc.insets = MEUI.dialogLabelInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(new JLabel("Position"), gbc);
    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogControlInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(mPosition = newTextField(POS_PROMPT, 15), gbc);

    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogLabelInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(new JLabel("Wave Shape"), gbc);
    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogControlInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(waveShape, gbc);

    grid(gbc, 0, y++, 2, 1);
    gbc.weighty = 0;
    gbc.insets = MEUI.dialogLabelInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(new JLabel("Wave Period"), gbc);
    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogControlInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(mWavePeriod = newTextField("1", 15), gbc);

    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogLabelInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(new JLabel("Attribute to cycle"), gbc);
    grid(gbc, 0, y++, 2, 1);
    gbc.insets = MEUI.dialogControlInsets();
    gbc.anchor = GridBagConstraints.CENTER;
    add(attribute, gbc);

    gbc.weighty = 1;
    grid(gbc, 0, y++, 2, 1);
    add(new JComponent() {
    }, gbc);
    gbc.weighty = 0;
    gbc.weightx = 1;
    gbc.insets = MEUI.dialogBottomButtonInsets();
    gbc.anchor = GridBagConstraints.SOUTHEAST;
    grid(gbc, 0, y++, 2, 1);
    JButton ok = new JButton("Add");
    add(ok, gbc);
    ok.addActionListener(e -> create());
  }

  @Override
  protected boolean populateDialog() {
    MeModel model = mMotionEditor.getMeModel();
    populateTags(model.getLayoutViewNames());
    MotionEditorSelector.Type selectionType = model.getSelectedType();
    if (selectionType == null) return false;
    MTag[] selected = model.getSelected();

    // ============ Add custom attributes defined in start or end constraintset
    MTag start = model.findStartConstraintSet(selected[0]);
    MTag end = model.findEndConstraintSet(selected[0]);
    ArrayList<MTag> csets  = new ArrayList<>();

    for (MTag tag : start.getChildTags()) {
      csets.addAll(Arrays.asList(tag.getChildTags(MotionSceneAttrs.Tags.CUSTOM_ATTRIBUTE)));
    }
    for (MTag tag : end.getChildTags()) {
      csets.addAll(Arrays.asList(tag.getChildTags(MotionSceneAttrs.Tags.CUSTOM_ATTRIBUTE)));
    }
    myCustomTypes.clear();

    for (MTag cset : csets) {
      String str = cset.getAttributeValue(MotionSceneAttrs.ATTR_CUSTOM_ATTRIBUTE_NAME);
      String value = cset.getAttributeValue(MotionSceneAttrs.ATTR_CUSTOM_FLOAT_VALUE);
      if (value != null) {
        myCustomTypes.put(str, cset);
      }
    }
    if (!myCustomTypes.isEmpty()) {
      attribute.removeAllItems();
      for (String option : attributeOptions) {
        attribute.addItem(option);
      }
      for (String s : myCustomTypes.keySet()) {
        attribute.addItem(CreateKeyAttribute.CUSTOM + s);
      }
    }
    // ==================================================

    switch (selectionType) {
      case KEY_FRAME:
      case KEY_FRAME_GROUP:
        mKeyFrameSet = selected[0].getParent();
        mMatchTag.setText(selected[0].getAttributeValue("motionTarget"));
        String wavePeriod = selected[0].getAttributeValue("wavePeriod");

        for (int i = 0; i < attributeOptions.length; i++) {
          String str = selected[0].getAttributeValue(attributeOptions[i]);
           if (str != null) {
            attribute.setSelectedIndex(i);
            break;
          }
        }

        break;
      case TRANSITION:
        MTag[] tag = selected[0].getChildTags("KeyFrameSet");
        if (tag != null && tag.length > 0) {
          mKeyFrameSet = tag[0];
        }
        mSelectedTransition = selected[0];
        break;
    }
    float pos = model.getCurrentProgress();
    if (!Float.isNaN(pos)) {
      mPosition.setText(Integer.toString((int) (pos * 100)));
    }
    if (DEBUG) {
      Debug.log("populateDialog " + selectionType + " " + Arrays.toString(model.getSelected()));
    }
    MTag[] mtags = model.motionScene.getChildTags("ConstraintSet");
    if (mtags.length < 2) {
      showPreconditionDialog("Transition must at least 2 ConstraintSets to create a Transition");
      return false;
    }

    return true;
  }

  @Override
  public MTag create() {
    if (DEBUG) {
      Debug.log("create");
    }
     MTag.TagWriter toCommit;
    MTag.TagWriter keyPosition;
    if (mKeyFrameSet == null) {
      mKeyFrameSet = toCommit = mSelectedTransition.getChildTagWriter(MotionSceneAttrs.Tags.KEY_FRAME_SET);
      keyPosition = mKeyFrameSet.getChildTagWriter(KEY_TAG);
    } else {
      toCommit = keyPosition = mKeyFrameSet.getChildTagWriter(KEY_TAG);
    }
    String pos = mPosition.getText();
    if (pos.trim().length() == 0 || pos.equals(POS_PROMPT)) {
      showErrorDialog("Must define the position of the view");
      return null;
    }

    keyPosition.setAttribute(MotionSceneAttrs.MOTION, MotionSceneAttrs.Key.MOTION_TARGET, getMotionTarget());
    try {
      int posInt = Integer.parseInt(pos.trim());
      keyPosition.setAttribute(MotionSceneAttrs.MOTION, MotionSceneAttrs.Key.FRAME_POSITION, pos.trim());
    } catch (Exception ex) {
      showErrorDialog("was not able to parse \"" + pos.trim() + "\"");
      return null;
    }
    keyPosition.setAttribute(MotionSceneAttrs.MOTION, MotionSceneAttrs.KeyCycle.WAVE_PERIOD, (String) mWavePeriod.getText());
    if (waveShape.getSelectedIndex() != 0) {
      keyPosition.setAttribute(MotionSceneAttrs.MOTION, MotionSceneAttrs.KeyCycle.WAVE_SHAPE, (String) waveShape.getSelectedItem());
    }
    int index = attribute.getSelectedIndex();
    if (index < MotionSceneAttrs.KeyCycleOptionsDefaultValue.length) {
    String value =  MotionSceneAttrs.KeyCycleOptionsDefaultValue[index];
    keyPosition.setAttribute(optionsNameSpace[index], (String) attribute.getSelectedItem(), value);
    String offset =  MotionSceneAttrs.KeyCycleOptionsDefaultOffset[index];
    keyPosition.setAttribute(MotionSceneAttrs.MOTION, MotionSceneAttrs.KeyCycle.WAVE_OFFSET , offset);

    } else {
      String item = (String) attribute.getSelectedItem();
      if (item.startsWith(CreateKeyAttribute.CUSTOM)) {
        String name = item.substring(CreateKeyAttribute.CUSTOM.length());
        MTag copyFromTag = myCustomTypes.get(name);
        MTag.TagWriter customTag = toCommit.getChildTagWriter(MotionSceneAttrs.Tags.CUSTOM_ATTRIBUTE);
        for (MTag.Attribute value :  copyFromTag.getAttrList().values()) {
          customTag.setAttribute(value.mNamespace,value.mAttribute,value.mValue);
        }
      }
    }
    MTag ret = toCommit.commit("Create KeyCycle");
    if (KEY_TAG.equals( MotionSceneAttrs.Tags.KEY_TIME_CYCLE)) {
      Track.createKeyTimeCycle(mMotionEditor.myTrack);
    } else {
      Track.createKeyCycle(mMotionEditor.myTrack);
    }
    mMotionEditor.dataChanged();
    super.create();
    return ret;
  }

  @Override
  public String getName() {
    return "KeyCycle";
  }

}
