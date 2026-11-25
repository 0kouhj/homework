"""Store opt-in account credentials in the current user's Windows vault."""

import ctypes
from contextlib import contextmanager
from ctypes import wintypes as win
from functools import cache

GENERIC = 1
LOCAL_MACHINE = 2
NOT_FOUND = 1168


class Credential(ctypes.Structure):
    _fields_ = [
        ("flags", win.DWORD),
        ("type", win.DWORD),
        ("target", win.LPWSTR),
        ("comment", win.LPWSTR),
        ("last_written", win.FILETIME),
        ("blob_size", win.DWORD),
        ("blob", ctypes.POINTER(ctypes.c_ubyte)),
        ("persist", win.DWORD),
        ("attribute_count", win.DWORD),
        ("attributes", ctypes.c_void_p),
        ("target_alias", win.LPWSTR),
        ("username", win.LPWSTR),
    ]


CredentialPointer = ctypes.POINTER(Credential)


@cache
def _api():
    if not hasattr(ctypes, "WinDLL"):
        raise OSError("Windows Credential Manager is unavailable")
    api = ctypes.WinDLL("Advapi32", use_last_error=True)
    api.CredReadW.argtypes = [win.LPCWSTR, win.DWORD, win.DWORD, ctypes.POINTER(CredentialPointer)]
    api.CredReadW.restype = win.BOOL
    api.CredWriteW.argtypes = [CredentialPointer, win.DWORD]
    api.CredWriteW.restype = win.BOOL
    api.CredDeleteW.argtypes = [win.LPCWSTR, win.DWORD, win.DWORD]
    api.CredDeleteW.restype = win.BOOL
    api.CredFree.argtypes = [ctypes.c_void_p]
    api.CredFree.restype = None
    return api


def _target(platform):
    return f"HomeworkScan/{platform}"


@contextmanager
def _read_entry(platform):
    api = _api()
    pointer = CredentialPointer()
    if not api.CredReadW(_target(platform), GENERIC, 0, ctypes.byref(pointer)):
        error = ctypes.get_last_error()
        if error == NOT_FOUND:
            yield None
            return
        raise ctypes.WinError(error)
    try:
        yield pointer.contents
    finally:
        api.CredFree(pointer)


def read_credentials(platform):
    with _read_entry(platform) as entry:
        if entry is None:
            return None
        return entry.username, ctypes.string_at(entry.blob, entry.blob_size).decode("utf-16-le")


def read_credential_username(platform):
    with _read_entry(platform) as entry:
        return None if entry is None else entry.username


def write_credentials(platform, username, password):
    payload = password.encode("utf-16-le")
    if len(payload) > 2560:
        raise ValueError("Password is too long for Windows Credential Manager")
    buffer = (ctypes.c_ubyte * len(payload)).from_buffer_copy(payload)
    entry = Credential(
        type=GENERIC, target=_target(platform), blob_size=len(buffer), blob=buffer,
        persist=LOCAL_MACHINE, username=username,
    )
    try:
        if not _api().CredWriteW(ctypes.byref(entry), 0):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        ctypes.memset(buffer, 0, len(buffer))


def delete_credentials(platform):
    if _api().CredDeleteW(_target(platform), GENERIC, 0):
        return True
    error = ctypes.get_last_error()
    if error == NOT_FOUND:
        return False
    raise ctypes.WinError(error)
