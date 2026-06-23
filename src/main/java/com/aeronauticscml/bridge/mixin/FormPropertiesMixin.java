package com.aeronauticscml.bridge.mixin;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import java.util.Map;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FormProperties.class)
public class FormPropertiesMixin {
    @Shadow
    private Map<String, KeyframeChannel> properties;

    @Inject(method = "applyProperties(Lmchorse/bbs_mod/forms/forms/Form;FF)V", at = @At("RETURN"))
    private void aeronauticscml$setEulerFromQuaternion(Form form, float tick, float partial, CallbackInfo ci) {
        if (form == null) return;
        if (!this.properties.containsKey("ac_qx")) return;

        float qx = interpolateFloat(this.properties.get("ac_qx"), tick);
        float qy = interpolateFloat(this.properties.get("ac_qy"), tick);
        float qz = interpolateFloat(this.properties.get("ac_qz"), tick);
        float qw = interpolateFloat(this.properties.get("ac_qw"), tick);

        Quaternionf q = new Quaternionf(qx, qy, qz, qw);
        if (q.lengthSquared() < 1e-6f) return;
        q.normalize();

        Vector3f euler = new Vector3f();
        q.getEulerAnglesZYX(euler);

        // applyTransform (MatrixStack) convention: Y(rot.z=yaw), X(rot.y=pitch), Z(rot.x=roll)
        // getEulerAnglesZYX returns (pitch, roll, yaw) in Rz(yaw)*Ry(roll)*Rx(pitch)
        // t.rotate = (roll, pitch, yaw) = (euler.y, euler.x, euler.z)
        ValueTransform valueTransform = form.transform;
        Transform t = valueTransform.get();
        if (t != null) {
            t.rotate.set(euler.y, euler.x, euler.z);
        }
    }

    private static float interpolateFloat(KeyframeChannel channel, float tick) {
        if (channel == null || channel.isEmpty()) return 0f;
        Object val = channel.interpolate(tick);
        if (val instanceof Number n) return n.floatValue();
        return 0f;
    }
}
