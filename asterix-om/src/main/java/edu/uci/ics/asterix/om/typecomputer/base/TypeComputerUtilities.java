package edu.uci.ics.asterix.om.typecomputer.base;

import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;

public class TypeComputerUtilities {

    public static void setOpenType(AbstractFunctionCallExpression expr, boolean openType) {
        Boolean openField = true;
        Object[] opaqueParameters = new Object[1];
        opaqueParameters[0] = openField;
        expr.setOpaqueParameters(opaqueParameters);
    }

    public static boolean isOpenType(AbstractFunctionCallExpression expr) {
        boolean openType = false;
        Object[] opaqueParameters = expr.getOpaqueParameters();
        if (opaqueParameters != null) {
            openType = (Boolean) opaqueParameters[0];
        }
        return openType;
    }

    public static boolean setRequiredType(AbstractFunctionCallExpression expr, ARecordType requiredRecordType) {
        boolean changed = false;
        Object opaqueParameter = expr.getOpaqueParameters();
        if (opaqueParameter == null) {
            opaqueParameter = requiredRecordType;
            Object[] opaqueParameters = new Object[1];
            opaqueParameters[0] = opaqueParameter;
            expr.setOpaqueParameters(opaqueParameters);
            changed = true;
        }
        return changed;
    }

    public static ARecordType getRequiredType(AbstractFunctionCallExpression expr) {
        Object[] type = expr.getOpaqueParameters();
        if (type != null) {
            ARecordType recordType = (ARecordType) type[0];
            return recordType;
        } else
            return null;
    }

}
