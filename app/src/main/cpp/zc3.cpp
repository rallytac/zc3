#include <jni.h>
#include <string>

static JavaVM *g_vm;
static jobject g_aec = nullptr;

static void ms_message(const char *fmt, ...)
{
}

static void ms_warning(const char *fmt, ...)
{
}

static void ms_error(const char *fmt, ...)
{
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env;

    g_vm = vm;
    if(g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR; // JNI version not supported.
    }

    return  JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnLoad(JavaVM* vm, void* reserved)
{
}

jobject ms_android_enable_hardware_echo_canceller(JNIEnv *env, int sessionId) {
    jobject aec = NULL;
    jclass aecClass = env->FindClass("android/media/audiofx/AcousticEchoCanceler");
    if (aecClass==NULL){
        ms_error("Couldn't find android/media/audiofx/AcousticEchoCanceler class !");
        env->ExceptionClear(); //very important.
        return NULL;
    }
    //aecClass= (jclass)env->NewGlobalRef(aecClass);
    jmethodID isAvailableID = env->GetStaticMethodID(aecClass,"isAvailable","()Z");
    if (isAvailableID!=NULL){
        jboolean ret=env->CallStaticBooleanMethod(aecClass,isAvailableID);
        if (ret){
            jmethodID createID = env->GetStaticMethodID(aecClass,"create","(I)Landroid/media/audiofx/AcousticEchoCanceler;");
            if (createID!=NULL){
                aec=env->CallStaticObjectMethod(aecClass,createID,sessionId);
                if (aec){
                    aec=env->NewGlobalRef(aec);
                    ms_message("AcousticEchoCanceler successfully created.");
                    jclass effectClass=env->FindClass("android/media/audiofx/AudioEffect");
                    if (effectClass){
                        //effectClass=(jclass)env->NewGlobalRef(effectClass);
                        jmethodID isEnabledID = env->GetMethodID(effectClass,"getEnabled","()Z");
                        jmethodID setEnabledID = env->GetMethodID(effectClass,"setEnabled","(Z)I");
                        if (isEnabledID && setEnabledID){
                            jboolean enabled=env->CallBooleanMethod(aec,isEnabledID);
                            ms_message("AcousticEchoCanceler enabled: %i",(int)enabled);
                            if (!enabled){
                                int ret=env->CallIntMethod(aec,setEnabledID,1);
                                if (ret!=0){
                                    ms_error("Could not enable AcousticEchoCanceler: %i",ret);
                                } else {
                                    ms_message("AcousticEchoCanceler enabled");
                                }
                            } else {
                                ms_warning("AcousticEchoCanceler already enabled");
                            }
                        } else {
                            ms_error("Couldn't find either getEnabled or setEnabled method in AudioEffect class for AcousticEchoCanceler !");
                        }
                        env->DeleteLocalRef(effectClass);
                    } else {
                        ms_error("Couldn't find android/media/audiofx/AudioEffect class !");
                    }
                }else{
                    ms_error("Failed to create AcousticEchoCanceler !");
                }
            }else{
                ms_error("create() not found in class AcousticEchoCanceler !");
                env->ExceptionClear(); //very important.
            }
        } else {
            ms_error("AcousticEchoCanceler isn't available !");
        }
    }else{
        ms_error("isAvailable() not found in class AcousticEchoCanceler !");
        env->ExceptionClear(); //very important.
    }
    env->DeleteLocalRef(aecClass);
    return aec;
}

void ms_android_delete_hardware_echo_canceller(JNIEnv *env, jobject aec) {
    env->DeleteGlobalRef(aec);
}


extern "C" JNIEXPORT void JNICALL Java_com_rallytac_zc3_MainActivity_nativeStartAudio(
        JNIEnv* env,
        jobject thiz,
        jint sessionId)
{
    JNIEnv *my_env;
    jint res;
    bool detach = false;

    res = g_vm->GetEnv((void**)&my_env, JNI_VERSION_1_6);

    if(res == JNI_EDETACHED)
    {
        res = g_vm->AttachCurrentThread(&my_env, nullptr);
        if(res != JNI_OK)
        {
            ms_error("g_vm->AttachCurrentThread failed");
            goto end_function;
        }

        detach = true;
    }
    else if(res != JNI_OK)
    {
        ms_error("g_vm->GetEnv failed");
        goto end_function;
    }

    g_aec = ms_android_enable_hardware_echo_canceller(my_env, (int)sessionId);

    end_function:
    if(detach)
    {
        g_vm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_rallytac_zc3_MainActivity_nativeStopAudio(
        JNIEnv* env,
        jobject /* this */)
{
    ms_android_delete_hardware_echo_canceller(env, g_aec);
    g_aec = nullptr;
}
