package dev.brikk.chill.quarantine.fixtures;

import java.util.ArrayList;
import java.util.List;

public class TypeAnnotatedOps extends ArrayList<@TypeMark String> {
    public List<@TypeMark String> values;

    @SuppressWarnings("unchecked")
    public List<@TypeMark String> copy(Object input) {
        List<@TypeMark String> local = (List<@TypeMark String>) input;
        try {
            return new ArrayList<@TypeMark String>(local);
        } catch (@TypeMark RuntimeException ex) {
            return null;
        }
    }
}
