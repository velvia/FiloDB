//! # Fast compressed vectors/arrays
//!
//! compvec is a "compressed vector" or compressed array library.  Have the same semantics of a vec or array,
//! with fast indexing and Iterator semantics/API, on highly compressed data.
//! Compare this to a traditional "compression" library, which requires you to fully deserialize encoded data
//! before working with it.

#[macro_use]
extern crate memoffset;

pub mod nibblepacking;
pub mod byteutils;
mod vector;
mod histogram;

use std::cell::RefCell;

// Define the external C API for interaction with JVM via JFFI, and potentially other sources.
// To minimize the chance of error, pointers to buffers sent over the API are one of two types:
// - BinaryRegionMedium: u16 at pointer contains # bytes of following buffer
// - BinaryRegionLarge: u32 at pointer contains # of bytes following
fn medium_slice_from_ptr(buf_len_ptr: *const u8) -> &'static [u8] {
    assert!(!buf_len_ptr.is_null());
    unsafe {
        let len = std::ptr::read_unaligned(buf_len_ptr as *const u16);
        std::slice::from_raw_parts(buf_len_ptr.add(2), len as usize)
    }
}

fn large_slice_from_ptr(buf_len_ptr: *const u8) -> &'static [u8] {
    assert!(!buf_len_ptr.is_null());
    unsafe {
        let len = std::ptr::read_unaligned(buf_len_ptr as *const u32);
        std::slice::from_raw_parts(buf_len_ptr.add(4), len as usize)
    }
}

use nibblepacking::DeltaSink;

static DEFAULT_BUF_CAPACITY: usize = 1024;

thread_local! {
    static DELTA_SINK: RefCell<DeltaSink> = RefCell::new(DeltaSink::new());
    static VEC_BUF: RefCell<Vec<u8>> = RefCell::new(Vec::with_capacity(DEFAULT_BUF_CAPACITY));
}

#[no_mangle]
pub extern "C" fn double_input(input: i32) -> i32 {
    input * 2
}

// fn: encode geometric + increasing (flag for geom -1)
// fn: encode geometric + non-increasing longs as increasing
#[no_mangle]
pub extern "C" fn compress_hist_geom_nonincreasing(num_buckets: usize,
                                                   initial_bucket: f64,
                                                   multiplier: f64,
                                                   format_code: histogram::BinHistogramFormat,
                                                   bucket_values: *const u64) -> *const u8 {
    // Check: initial_bucket, etc. etc.
    let mut vec_ptr: *const u8;
    VEC_BUF.with(|outbuf_vec| {
        let mut outbuf = outbuf_vec.borrow_mut();
        outbuf.clear();
        outbuf.push(0);   // Push empty initial 2 length bytes -- we'll come back to fill it out later
        outbuf.push(0);
        let values = unsafe { std::slice::from_raw_parts(bucket_values, num_buckets) };
        histogram::compress_geom_nonincreasing(
          num_buckets as u16, initial_bucket, multiplier, format_code, values, &mut outbuf);
        vec_ptr = outbuf.as_ptr();
    });
    vec_ptr
}
// fn: encode geometric + doubles XOR
//

/// Unpacks a byte buffer with given length which was delta encoded.
/// Returns the pointer to a thread-local buffer (backed by a Vec) with at least num_elements u64's in it, or
/// 0 if there was an error (input too short, etc.)
#[no_mangle]
pub extern "C" fn nibblepack_unpack_delta_u64(encoded_buf: *const u8, num_bytes: i32, num_values: i32) -> *const u64 {
    if num_bytes <= 0 || num_values <= 0 {
        0 as *const u64   // Yuck.  Can we return something better than null?
    } else {
        let inbuf = unsafe { std::slice::from_raw_parts(encoded_buf, num_bytes as usize) };
        // get output buf, and reset it
        DELTA_SINK.with(|sinkcell| {
            let sink = &mut *sinkcell.borrow_mut();
            match nibblepacking::unpack(inbuf, sink, num_values as usize) {
                Ok(_)  => sink.get_ptr(),
                Err(_) => 0 as *const u64
            }
        })
    }
}

// fn: decode double XOR buckets only
// fn: encode explicit buckets + increasing