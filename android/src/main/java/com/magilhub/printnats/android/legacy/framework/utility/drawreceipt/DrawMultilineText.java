// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.utility.drawreceipt;


import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class DrawMultilineText extends DrawText {

    public static final String END_OF_TEXT = "\u0003";
    private final int width;
    private final boolean wrapText;
    private final List<String> linesOfText = new ArrayList<>();
    private int linesCount;

    public DrawMultilineText(String text, int width, float textSize, int color, Typeface typeface, Paint.Align align, boolean wrapText) {
        super(text);
        this.width = width;
        this.wrapText = wrapText;

        super.setTextSize(textSize);
        super.setColor(color);
        super.setNewLine(true);

        if (typeface != null) {
            super.setTypeface(typeface);
        }
        if (align != null) {
            super.setAlign(align);
        }
        splitText();
    }

    @Override
    public void drawOnCanvas(Canvas canvas, float x, float y) {
        drawMultilineText(x, y, paint, canvas);
    }

    @Override
    public int getHeight() {
        return linesCount * getTextHeight();
    }


    private void drawMultilineText(float x, float y, Paint paint, Canvas canvas){
        float xx = getX(canvas, x);
        float yy = getY(y);
        float height = 0;
        for (String line: linesOfText){
            canvas.drawText(line, xx, yy + height, paint);
            height += getTextHeight();
        }
    }

    private void makeSecureLinesWrapText(String text) {

        if (TextUtils.isEmpty(text)) {
            linesOfText.add("");
        }

        //measuredNum used as the end of line
        int measuredNum = paint.breakText(text, true, width, null);
        String subString = text.substring(0, measuredNum);
        if (subString.contains(END_OF_TEXT)){
            linesOfText.add(subString.replace(END_OF_TEXT, ""));
        }
        else {
            int lastIndexOfSpace;
            int endIndex = measuredNum;

            //if true measured text fits in a line
            if (text.charAt(measuredNum) == ' '){
                measuredNum +=1; //increasing to skip space on the next line
                linesOfText.add(text.substring(0, endIndex));
            }

            //if true endIndex is changed to last space in a substring
            else if (( lastIndexOfSpace = subString.lastIndexOf( ' ') ) > 0) {
                endIndex = lastIndexOfSpace;
                measuredNum = lastIndexOfSpace+1; //increasing to skip a space on the next line
                linesOfText.add(text.substring(0, endIndex));
            }
            //No spaces found we split word and add "-" at the end of the word
            else {
                measuredNum -= 1;
                linesOfText.add(text.substring(0, measuredNum)+"-");
            }
            String leftStr = text.substring(measuredNum);

            if(leftStr.length() > 0) {
                makeSecureLinesWrapText(leftStr);
            }
        }
    }

    private void makeSecureLines(String text) {

        if (TextUtils.isEmpty(text)) {
            linesOfText.add("");
        }

        int measuredNum = paint.breakText(text, true, width, null);

        linesOfText.add(text.substring(0, measuredNum));
        String leftStr = text.substring(measuredNum);

        if(leftStr.length() > 0) makeSecureLines(leftStr);
    }

    private void splitText(){
        String[] lines = text.split("\n");
        if (wrapText){
            for (String line: lines){
                makeSecureLinesWrapText(line);
            }
        }else{
            for (String line: lines){
                makeSecureLines(line);
            }
        }

        linesCount = linesOfText.size();
    }

}
